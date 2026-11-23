package dev.securemessenger.data

import androidx.room.withTransaction
import dev.securemessenger.data.e2ee.ChatPayload
import dev.securemessenger.data.e2ee.DuplicateEnvelopeException
import dev.securemessenger.data.e2ee.E2eeManager
import dev.securemessenger.data.local.AppDatabase
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.data.local.OutboxEntity
import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.UserResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID

class MessageRepository(
    private val database: AppDatabase,
    private val e2ee: E2eeManager,
    private val accounts: AuthRepository,
    private val json: Json,
) {
    fun observeMessages(accountId: String): Flow<List<MessageEntity>> =
        database.messages().observeChat("direct:$accountId")

    suspend fun sendText(user: UserResponse, body: String): String {
        e2ee.initializeDevice()
        val messageId = UUID.randomUUID().toString()
        val request = e2ee.encryptText(user, messageId, body)
        database.withTransaction {
            database.messages().insert(
                MessageEntity(messageId, "direct:${user.accountId}", body, true, System.currentTimeMillis(), "PENDING"),
            )
            database.outboxes().put(OutboxEntity(messageId, json.encodeToString(SendMessageRequest.serializer(), request), System.currentTimeMillis()))
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
        accounts.fetchDelivered().messageIds.forEach { database.messages().markDelivered(it) }
    }

    suspend fun markChatRead(user: UserResponse) {
        val unread = database.messages().unreadIncoming("direct:${user.accountId}")
        unread.forEach { message ->
            val request = e2ee.encryptReadReceipt(user, message.messageId)
            database.outboxes().put(OutboxEntity(request.messageId, json.encodeToString(SendMessageRequest.serializer(), request), System.currentTimeMillis()))
            database.messages().updateStatus(message.messageId, "READ")
        }
        flushOutbox()
    }

    private suspend fun handlePayload(senderAccountId: String, payload: ChatPayload) {
        when (payload.kind) {
            ChatPayload.TEXT -> database.messages().insert(
                MessageEntity(
                    messageId = payload.messageId,
                    chatId = "direct:$senderAccountId",
                    body = payload.body,
                    outgoing = false,
                    createdAt = payload.createdAt,
                    status = "DELIVERED",
                ),
            )
            ChatPayload.READ_RECEIPT -> database.messages().updateStatus(payload.messageId, "READ")
        }
    }

    private suspend fun flushOutbox() {
        database.outboxes().all().forEach { outbox ->
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
        database.messages().updateStatus(request.messageId, "SENT")
        database.outboxes().delete(request.messageId)
    }
}
