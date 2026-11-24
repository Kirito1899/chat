package dev.securemessenger.data

import androidx.room.withTransaction
import dev.securemessenger.data.e2ee.ChatPayload
import dev.securemessenger.data.e2ee.DuplicateEnvelopeException
import dev.securemessenger.data.e2ee.E2eeManager
import dev.securemessenger.data.e2ee.SafetyNumber
import dev.securemessenger.data.local.AppDatabase
import dev.securemessenger.data.local.MessageDao
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.data.local.OutboxDao
import dev.securemessenger.data.local.OutboxEntity
import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.shared.DeliveredMessagesResponse
import dev.securemessenger.shared.DeliveryResponse
import dev.securemessenger.shared.IncomingMessage
import dev.securemessenger.shared.MessageListResponse
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.UserResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID

internal interface MessageCipher {
    suspend fun initializeDevice()
    suspend fun encryptText(user: UserResponse, messageId: String, body: String): SendMessageRequest
    suspend fun encryptReadReceipt(user: UserResponse, originalMessageId: String): SendMessageRequest
    suspend fun decrypt(message: IncomingMessage): ChatPayload
    suspend fun safetyNumber(user: UserResponse): SafetyNumber
    suspend fun acceptChangedIdentity(user: UserResponse)
}

internal interface MessageAccounts {
    suspend fun sendMessage(request: SendMessageRequest): DeliveryResponse
    suspend fun fetchMessages(): MessageListResponse
    suspend fun markDelivered(messageId: String)
    suspend fun fetchDelivered(): DeliveredMessagesResponse
}

internal fun interface MessageTransaction {
    suspend fun run(block: suspend () -> Unit)
}

class MessageRepository internal constructor(
    private val messages: MessageDao,
    private val outboxes: OutboxDao,
    private val e2ee: MessageCipher,
    private val accounts: MessageAccounts,
    private val json: Json,
    private val transact: MessageTransaction,
) {
    constructor(
        database: AppDatabase,
        e2ee: E2eeManager,
        accounts: AuthRepository,
        json: Json,
    ) : this(
        messages = database.messages(),
        outboxes = database.outboxes(),
        e2ee = object : MessageCipher {
            override suspend fun initializeDevice() = e2ee.initializeDevice()
            override suspend fun encryptText(user: UserResponse, messageId: String, body: String) =
                e2ee.encryptText(user, messageId, body)
            override suspend fun encryptReadReceipt(user: UserResponse, originalMessageId: String) =
                e2ee.encryptReadReceipt(user, originalMessageId)
            override suspend fun decrypt(message: IncomingMessage) = e2ee.decrypt(message)
            override suspend fun safetyNumber(user: UserResponse): SafetyNumber = e2ee.safetyNumber(user)
            override suspend fun acceptChangedIdentity(user: UserResponse) = e2ee.acceptChangedIdentity(user)
        },
        accounts = object : MessageAccounts {
            override suspend fun sendMessage(request: SendMessageRequest) = accounts.sendMessage(request)
            override suspend fun fetchMessages() = accounts.fetchMessages()
            override suspend fun markDelivered(messageId: String) = accounts.markDelivered(messageId)
            override suspend fun fetchDelivered() = accounts.fetchDelivered()
        },
        json = json,
        transact = MessageTransaction { block -> database.withTransaction { block() } },
    )

    fun observeMessages(accountId: String): Flow<List<MessageEntity>> =
        messages.observeChat("direct:$accountId")

    suspend fun safetyNumber(user: UserResponse): SafetyNumber = e2ee.safetyNumber(user)
    suspend fun acceptChangedIdentity(user: UserResponse) = e2ee.acceptChangedIdentity(user)

    suspend fun sendText(user: UserResponse, body: String): String {
        e2ee.initializeDevice()
        val messageId = UUID.randomUUID().toString()
        val request = e2ee.encryptText(user, messageId, body)
        transact.run {
            messages.insert(
                MessageEntity(messageId, "direct:${user.accountId}", body, true, System.currentTimeMillis(), "PENDING"),
            )
            outboxes.put(OutboxEntity(messageId, json.encodeToString(SendMessageRequest.serializer(), request), System.currentTimeMillis()))
        }
        try {
            sendRequest(request)
        } catch (_: IOException) {
        } catch (_: ApiException) {
        }
        return messageId
    }

    suspend fun sync() {
        e2ee.initializeDevice()
        flushOutbox()
        val incoming = accounts.fetchMessages().messages
        incoming.forEach { message ->
            try {
                val payload = e2ee.decrypt(message)
                handlePayload(message.senderAccountId, payload)
                accounts.markDelivered(message.messageId)
            } catch (_: DuplicateEnvelopeException) {
                accounts.markDelivered(message.messageId)
            }
        }
        accounts.fetchDelivered().messageIds.forEach { messages.markDelivered(it) }
    }

    suspend fun markChatRead(user: UserResponse) {
        val unread = messages.unreadIncoming("direct:${user.accountId}")
        unread.forEach { message ->
            val request = e2ee.encryptReadReceipt(user, message.messageId)
            outboxes.put(OutboxEntity(request.messageId, json.encodeToString(SendMessageRequest.serializer(), request), System.currentTimeMillis()))
            messages.updateStatus(message.messageId, "READ")
        }
        flushOutbox()
    }

    private suspend fun handlePayload(senderAccountId: String, payload: ChatPayload) {
        when (payload.kind) {
            ChatPayload.TEXT -> {
                val inserted = messages.insert(
                    MessageEntity(
                        messageId = payload.messageId,
                        chatId = "direct:$senderAccountId",
                        body = payload.body,
                        outgoing = false,
                        createdAt = payload.createdAt,
                        status = "DELIVERED",
                    ),
                )
                if (inserted == -1L && messages.byId(payload.messageId) == null) {
                    throw IllegalStateException("Incoming message was not persisted")
                }
            }
            ChatPayload.READ_RECEIPT -> messages.updateStatusInChat(payload.messageId, "direct:$senderAccountId", "READ")
        }
    }

    private suspend fun flushOutbox() {
        outboxes.all().forEach { outbox ->
            val request = json.decodeFromString<SendMessageRequest>(outbox.requestJson)
            try {
                sendRequest(request)
            } catch (_: IOException) {
                return
            } catch (_: ApiException) {
                return
            }
        }
    }

    private suspend fun sendRequest(request: SendMessageRequest) {
        accounts.sendMessage(request)
        messages.updateStatus(request.messageId, "SENT")
        outboxes.delete(request.messageId)
    }
}
