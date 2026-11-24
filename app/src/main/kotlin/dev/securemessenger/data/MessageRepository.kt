package dev.securemessenger.data

import androidx.room.withTransaction
import dev.securemessenger.data.e2ee.ChatPayload
import dev.securemessenger.data.e2ee.DuplicateEnvelopeException
import dev.securemessenger.data.e2ee.E2eeManager
import dev.securemessenger.data.e2ee.MediaDescriptor
import dev.securemessenger.data.e2ee.SafetyNumber
import dev.securemessenger.data.local.AppDatabase
import dev.securemessenger.data.local.GroupInfoDao
import dev.securemessenger.data.local.GroupInfoEntity
import dev.securemessenger.data.local.GroupReceiptDao
import dev.securemessenger.data.local.GroupReceiptEntity
import dev.securemessenger.data.local.MessageDao
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.data.local.OutboxDao
import dev.securemessenger.data.local.OutboxEntity
import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.shared.DeliveredMessagesResponse
import dev.securemessenger.shared.DeliveryResponse
import dev.securemessenger.shared.GroupResponse
import dev.securemessenger.shared.IncomingMessage
import dev.securemessenger.shared.MessageListResponse
import dev.securemessenger.shared.SendGroupMessageRequest
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.UserResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID

internal interface MessageCipher {
    suspend fun initializeDevice()
    suspend fun encryptText(user: UserResponse, messageId: String, body: String): SendMessageRequest
    suspend fun encryptReadReceipt(user: UserResponse, originalMessageId: String): SendMessageRequest
    suspend fun encryptDirectPayload(user: UserResponse, payload: ChatPayload): SendMessageRequest =
        throw UnsupportedOperationException()
    suspend fun encryptPayload(user: UserResponse, payload: ChatPayload): SendMessageRequest =
        throw UnsupportedOperationException()
    suspend fun decrypt(message: IncomingMessage): ChatPayload
    suspend fun safetyNumber(user: UserResponse): SafetyNumber
    suspend fun acceptChangedIdentity(user: UserResponse)
}

internal interface MessageAccounts {
    suspend fun sendMessage(request: SendMessageRequest): DeliveryResponse
    suspend fun fetchMessages(): MessageListResponse
    suspend fun markDelivered(messageId: String)
    suspend fun fetchDelivered(): DeliveredMessagesResponse
    suspend fun sendGroupMessage(groupId: String, request: SendGroupMessageRequest): DeliveryResponse =
        throw UnsupportedOperationException()
    suspend fun currentAccountId(): String? = null
    suspend fun fetchGroup(groupId: String): GroupResponse = throw UnsupportedOperationException()
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
    private val groupInfo: GroupInfoDao? = null,
    private val groupReceipts: GroupReceiptDao? = null,
    private val transact: MessageTransaction,
) {
    private val syncMutex = Mutex()

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
            override suspend fun encryptDirectPayload(user: UserResponse, payload: ChatPayload) =
                e2ee.encryptDirectPayload(user, payload)
            override suspend fun encryptPayload(user: UserResponse, payload: ChatPayload) =
                e2ee.encryptPayload(user, payload)
            override suspend fun decrypt(message: IncomingMessage) = e2ee.decrypt(message)
            override suspend fun safetyNumber(user: UserResponse): SafetyNumber = e2ee.safetyNumber(user)
            override suspend fun acceptChangedIdentity(user: UserResponse) = e2ee.acceptChangedIdentity(user)
        },
        accounts = object : MessageAccounts {
            override suspend fun sendMessage(request: SendMessageRequest) = accounts.sendMessage(request)
            override suspend fun fetchMessages() = accounts.fetchMessages()
            override suspend fun markDelivered(messageId: String) = accounts.markDelivered(messageId)
            override suspend fun fetchDelivered() = accounts.fetchDelivered()
            override suspend fun sendGroupMessage(groupId: String, request: SendGroupMessageRequest) =
                accounts.sendGroupMessage(groupId, request)
            override suspend fun currentAccountId(): String? = accounts.restore()?.accountId
            override suspend fun fetchGroup(groupId: String): GroupResponse = accounts.getGroup(groupId)
        },
        json = json,
        transact = MessageTransaction { block -> database.withTransaction { block() } },
        groupInfo = database.groupInfo(),
        groupReceipts = database.groupReceipts(),
    )

    fun observeMessages(accountId: String): Flow<List<MessageEntity>> =
        messages.observeChat("direct:$accountId")

    fun observeGroupMessages(groupId: String): Flow<List<MessageEntity>> {
        val receipts = requireNotNull(groupReceipts).observeAll()
        return combine(messages.observeChat("group:$groupId"), receipts) { chat, states ->
            chat.map { message ->
                if (!message.outgoing) return@map message
                val recipients = states.filter { it.groupMessageId == message.messageId }
                if (recipients.isEmpty()) return@map message
                val active = recipients.filter { it.state != "SKIPPED" }
                val status = when {
                    active.isEmpty() -> "SKIPPED"
                    active.all { it.state == "READ" } -> "READ"
                    active.all { it.state == "DELIVERED" || it.state == "READ" } -> "DELIVERED"
                    active.all { it.state != "PENDING" } -> "SENT"
                    else -> "PENDING"
                }
                message.copy(status = status)
            }
        }
    }

    suspend fun safetyNumber(user: UserResponse): SafetyNumber = e2ee.safetyNumber(user)
    suspend fun acceptChangedIdentity(user: UserResponse) = e2ee.acceptChangedIdentity(user)

    suspend fun sendText(user: UserResponse, body: String): String {
        e2ee.initializeDevice()
        val messageId = UUID.randomUUID().toString()
        val request = e2ee.encryptText(user, messageId, body)
        val outbox = OutboxEntity(messageId, json.encodeToString(SendMessageRequest.serializer(), request), System.currentTimeMillis())
        transact.run {
            messages.insert(
                MessageEntity(messageId, "direct:${user.accountId}", body, true, System.currentTimeMillis(), "PENDING"),
            )
            outboxes.put(outbox)
        }
        try {
            sendRequest(outbox)
        } catch (_: IOException) {
        } catch (_: ApiException) {
        }
        return messageId
    }

    suspend fun sendMedia(user: UserResponse, descriptor: MediaDescriptor, caption: String): String {
        val payload = ChatPayload(
            ChatPayload.MEDIA,
            UUID.randomUUID().toString(),
            caption,
            System.currentTimeMillis(),
            media = descriptor,
        )
        e2ee.initializeDevice()
        val request = e2ee.encryptDirectPayload(user, payload)
        val outbox = OutboxEntity(payload.messageId, json.encodeToString(SendMessageRequest.serializer(), request), System.currentTimeMillis())
        transact.run {
            messages.insert(messageEntity(payload, outgoing = true, chatId = "direct:${user.accountId}"))
            outboxes.put(outbox)
        }
        try {
            sendRequest(outbox)
        } catch (_: IOException) {
        } catch (_: ApiException) {
        }
        return payload.messageId
    }

    suspend fun sendGroupText(group: GroupResponse, body: String): String {
        val payload = ChatPayload(ChatPayload.GROUP_TEXT, UUID.randomUUID().toString(), body, System.currentTimeMillis(), group.groupId)
        return sendGroupPayload(group, payload)
    }

    suspend fun sendGroupMedia(group: GroupResponse, descriptor: MediaDescriptor, caption: String): String {
        val payload = ChatPayload(
            ChatPayload.GROUP_MEDIA,
            UUID.randomUUID().toString(),
            caption,
            System.currentTimeMillis(),
            group.groupId,
            descriptor,
        )
        return sendGroupPayload(group, payload)
    }

    suspend fun sendGroupInfo(group: GroupResponse, title: String, recipientAccountIds: Set<String>? = null): String {
        val payload = ChatPayload(ChatPayload.GROUP_INFO, UUID.randomUUID().toString(), title, System.currentTimeMillis(), group.groupId)
        return sendGroupPayload(group, payload, recipientAccountIds, trackMessage = false)
    }

    private suspend fun sendGroupPayload(
        group: GroupResponse,
        payload: ChatPayload,
        recipientAccountIds: Set<String>? = null,
        trackMessage: Boolean = true,
    ): String {
        require(payload.groupId == group.groupId) { "Group payload context mismatch" }
        e2ee.initializeDevice()
        val localAccountId = requireNotNull(accounts.currentAccountId()) { "No local account" }
        val recipients = group.members.filter {
            it.accountId != localAccountId && (recipientAccountIds == null || it.accountId in recipientAccountIds)
        }
        require(recipients.isNotEmpty()) { "No group recipients" }
        val requests = recipients.map { member ->
            val user = UserResponse(member.accountId, member.username)
            e2ee.encryptPayload(user, payload)
        }
        transact.run {
            if (trackMessage) {
                messages.insert(messageEntity(payload, outgoing = true, chatId = "group:${group.groupId}", senderAccountId = localAccountId))
            }
            requests.forEach { request ->
                outboxes.put(
                    OutboxEntity(
                        messageId = payload.messageId,
                        requestJson = json.encodeToString(SendMessageRequest.serializer(), request),
                        createdAt = System.currentTimeMillis(),
                        envelopeId = request.messageId,
                        groupId = group.groupId,
                    ),
                )
                if (trackMessage) {
                    groupReceipts?.insert(GroupReceiptEntity(request.messageId, payload.messageId, request.recipientDeviceId, "PENDING"))
                }
            }
        }
        flushOutbox()
        return payload.messageId
    }

    suspend fun sync() = syncMutex.withLock {
        e2ee.initializeDevice()
        flushOutbox()
        val incoming = accounts.fetchMessages().messages
        incoming.forEach { message ->
            try {
                val payload = e2ee.decrypt(message)
                handlePayload(message, payload)
                accounts.markDelivered(message.messageId)
            } catch (_: DuplicateEnvelopeException) {
                accounts.markDelivered(message.messageId)
            }
        }
        accounts.fetchDelivered().messageIds.forEach {
            messages.markDelivered(it)
            groupReceipts?.markDelivered(it)
        }
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

    suspend fun markGroupRead(group: GroupResponse) {
        val unread = messages.unreadIncoming("group:${group.groupId}")
        val byAccount = group.members.associateBy { it.accountId }
        unread.forEach { message ->
            val senderAccount = message.senderAccountId ?: return@forEach
            val sender = byAccount[senderAccount] ?: return@forEach
            val payload = ChatPayload(
                ChatPayload.GROUP_READ_RECEIPT,
                message.messageId,
                createdAt = System.currentTimeMillis(),
                groupId = group.groupId,
            )
            val request = e2ee.encryptPayload(UserResponse(sender.accountId, sender.username), payload)
            outboxes.put(
                OutboxEntity(
                    messageId = UUID.randomUUID().toString(),
                    requestJson = json.encodeToString(SendMessageRequest.serializer(), request),
                    createdAt = System.currentTimeMillis(),
                    envelopeId = request.messageId,
                    groupId = group.groupId,
                ),
            )
            messages.updateStatusInChat(message.messageId, "group:${group.groupId}", "READ")
        }
        flushOutbox()
    }

    private suspend fun handlePayload(envelope: IncomingMessage, payload: ChatPayload) {
        when (payload.kind) {
            ChatPayload.TEXT, ChatPayload.MEDIA -> insertPayload(envelope, payload, "direct:${envelope.senderAccountId}")
            ChatPayload.GROUP_TEXT, ChatPayload.GROUP_MEDIA -> {
                val groupId = requireNotNull(payload.groupId)
                insertPayload(envelope, payload, "group:$groupId")
            }
            ChatPayload.GROUP_INFO -> {
                val group = accounts.fetchGroup(requireNotNull(payload.groupId))
                if (group.ownerAccountId == envelope.senderAccountId) {
                    groupInfo?.upsert(GroupInfoEntity(group.groupId, payload.body, group.ownerAccountId))
                }
            }
            ChatPayload.READ_RECEIPT -> messages.updateStatusInChat(payload.messageId, "direct:${envelope.senderAccountId}", "READ")
            ChatPayload.GROUP_READ_RECEIPT -> groupReceipts?.markRead(payload.messageId, envelope.senderDeviceId)
            else -> throw IllegalArgumentException("Unsupported chat payload")
        }
    }

    private suspend fun insertPayload(envelope: IncomingMessage, payload: ChatPayload, chatId: String) {
        val inserted = messages.insert(messageEntity(payload, outgoing = false, chatId = chatId, senderAccountId = envelope.senderAccountId))
        if (inserted == -1L && messages.byIdInChat(payload.messageId, chatId) == null) {
            throw IllegalStateException("Incoming message was not persisted")
        }
    }

    private fun messageEntity(payload: ChatPayload, outgoing: Boolean, chatId: String, senderAccountId: String? = null) = MessageEntity(
        messageId = payload.messageId,
        chatId = chatId,
        body = payload.body,
        outgoing = outgoing,
        createdAt = payload.createdAt,
        status = if (outgoing) "PENDING" else "DELIVERED",
        mediaJson = payload.media?.let { json.encodeToString(MediaDescriptor.serializer(), it) },
        senderAccountId = senderAccountId,
    )

    private suspend fun flushOutbox() {
        outboxes.all().forEach { outbox ->
            val request = json.decodeFromString<SendMessageRequest>(outbox.requestJson)
            try {
                sendRequest(outbox, request)
            } catch (_: IOException) {
                return
            } catch (error: ApiException) {
                if (outbox.groupId != null && error.code == "RECIPIENT_NOT_FOUND") {
                    groupReceipts?.markSkipped(request.messageId)
                    outboxes.delete(outbox.envelopeId)
                    return@forEach
                }
                return
            }
        }
    }

    private suspend fun sendRequest(outbox: OutboxEntity, decoded: SendMessageRequest? = null) {
        val request = decoded ?: json.decodeFromString<SendMessageRequest>(outbox.requestJson)
        if (outbox.groupId == null) {
            accounts.sendMessage(request)
            messages.updateStatus(request.messageId, "SENT")
        } else {
            accounts.sendGroupMessage(outbox.groupId, SendGroupMessageRequest(outbox.messageId, request))
            groupReceipts?.markSent(request.messageId)
        }
        outboxes.delete(outbox.envelopeId)
    }
}
