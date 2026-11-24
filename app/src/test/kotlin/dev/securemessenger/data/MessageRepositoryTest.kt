package dev.securemessenger.data

import dev.securemessenger.data.e2ee.ChatPayload
import dev.securemessenger.data.e2ee.DuplicateEnvelopeException
import dev.securemessenger.data.e2ee.SafetyNumber
import dev.securemessenger.data.e2ee.SafetyNumberParty
import dev.securemessenger.data.e2ee.SafetyNumberService
import dev.securemessenger.data.local.MessageDao
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.data.local.OutboxDao
import dev.securemessenger.data.local.OutboxEntity
import dev.securemessenger.shared.DeliveredMessagesResponse
import dev.securemessenger.shared.DeliveryResponse
import dev.securemessenger.shared.IncomingMessage
import dev.securemessenger.shared.MessageListResponse
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.UserResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageRepositoryTest {
    private val json = Json
    private val user = UserResponse(UUID.randomUUID().toString(), "remote_user")
    private lateinit var events: MutableList<String>
    private lateinit var messages: FakeMessageDao
    private lateinit var outboxes: FakeOutboxDao
    private lateinit var cipher: FakeMessageCipher
    private lateinit var accounts: FakeMessageAccounts
    private lateinit var repository: MessageRepository

    @BeforeTest
    fun setUp() {
        events = mutableListOf()
        messages = FakeMessageDao(events)
        outboxes = FakeOutboxDao(events)
        cipher = FakeMessageCipher()
        accounts = FakeMessageAccounts(events)
        repository = MessageRepository(messages, outboxes, cipher, accounts, json) { block -> block() }
    }

    @Test
    fun `sendText saves encrypted outbox entry before network without plaintext in request`() = runTest {
        val body = "private message body"
        accounts.onSend = { request ->
            val pending = assertNotNull(outboxes.items[request.messageId])
            assertEquals(request, json.decodeFromString(SendMessageRequest.serializer(), pending.requestJson))
            assertEquals("PENDING", messages.byId(request.messageId)?.status)
        }

        val messageId = repository.sendText(user, body)

        val sent = accounts.sent.single()
        assertEquals(messageId, sent.messageId)
        assertFalse(body in sent.ciphertext)
        assertFalse(body in json.encodeToString(SendMessageRequest.serializer(), sent))
        assertTrue(outboxes.items.isEmpty())
        assertEquals("SENT", messages.byId(messageId)?.status)
    }

    @Test
    fun `failed send retries identical outbox request`() = runTest {
        accounts.failNextSend = true
        val messageId = repository.sendText(user, "pending body")
        val pending = assertNotNull(outboxes.items[messageId])
        val firstRequest = json.decodeFromString(SendMessageRequest.serializer(), pending.requestJson)
        assertEquals("PENDING", messages.byId(messageId)?.status)

        repository.sync()

        assertEquals(listOf(firstRequest, firstRequest), accounts.sent)
        assertTrue(outboxes.items.isEmpty())
        assertEquals("SENT", messages.byId(messageId)?.status)
    }

    @Test
    fun `sync durably saves incoming text before acknowledgement`() = runTest {
        val incoming = incomingMessage()
        accounts.incoming = listOf(incoming)
        cipher.onDecrypt = {
            ChatPayload(ChatPayload.TEXT, incoming.messageId, "incoming body", 123L)
        }

        repository.sync()

        assertEquals("incoming body", messages.byId(incoming.messageId)?.body)
        assertEquals(listOf(incoming.messageId), accounts.acknowledged)
        assertTrue(events.indexOf("insert:${incoming.messageId}") < events.indexOf("ack:${incoming.messageId}"))
    }

    @Test
    fun `sync does not acknowledge when incoming text was not saved`() = runTest {
        val incoming = incomingMessage()
        accounts.incoming = listOf(incoming)
        messages.rejectNextInsert = true
        cipher.onDecrypt = {
            ChatPayload(ChatPayload.TEXT, incoming.messageId, "incoming body", 123L)
        }

        assertFailsWith<IllegalStateException> { repository.sync() }

        assertNull(messages.byId(incoming.messageId))
        assertTrue(accounts.acknowledged.isEmpty())
    }

    @Test
    fun `sync does not acknowledge decrypt failure`() = runTest {
        val incoming = incomingMessage()
        accounts.incoming = listOf(incoming)
        cipher.onDecrypt = { throw IllegalArgumentException("Invalid ciphertext") }

        assertFailsWith<IllegalArgumentException> { repository.sync() }

        assertTrue(accounts.acknowledged.isEmpty())
    }

    @Test
    fun `sync acknowledges an already saved duplicate exactly once per sync`() = runTest {
        val incoming = incomingMessage()
        messages.put(
            MessageEntity(incoming.messageId, "direct:${user.accountId}", "saved", false, 123L, "DELIVERED"),
        )
        accounts.incoming = listOf(incoming)
        cipher.onDecrypt = { throw DuplicateEnvelopeException() }

        repository.sync()

        assertEquals(listOf(incoming.messageId), accounts.acknowledged)
    }

    @Test
    fun `matching read receipt marks outgoing message read`() = runTest {
        val originalId = UUID.randomUUID().toString()
        messages.put(MessageEntity(originalId, "direct:${user.accountId}", "outgoing", true, 123L, "SENT"))
        val incoming = incomingMessage()
        accounts.incoming = listOf(incoming)
        cipher.onDecrypt = { ChatPayload(ChatPayload.READ_RECEIPT, originalId, createdAt = 124L) }

        repository.sync()

        assertEquals("READ", messages.byId(originalId)?.status)
        assertEquals(listOf(incoming.messageId), accounts.acknowledged)
    }

    @Test
    fun `read receipt cannot change a message in another chat`() = runTest {
        val originalId = UUID.randomUUID().toString()
        messages.put(MessageEntity(originalId, "direct:${UUID.randomUUID()}", "outgoing", true, 123L, "SENT"))
        val incoming = incomingMessage()
        accounts.incoming = listOf(incoming)
        cipher.onDecrypt = { ChatPayload(ChatPayload.READ_RECEIPT, originalId, createdAt = 124L) }

        repository.sync()

        assertEquals("SENT", messages.byId(originalId)?.status)
        assertEquals(listOf(incoming.messageId), accounts.acknowledged)
    }

    @Test
    fun `failed read receipt remains idempotent in outbox`() = runTest {
        val originalId = UUID.randomUUID().toString()
        messages.put(MessageEntity(originalId, "direct:${user.accountId}", "incoming", false, 123L, "DELIVERED"))
        accounts.failNextSend = true

        repository.markChatRead(user)
        val firstRequest = accounts.sent.single()
        assertEquals("READ", messages.byId(originalId)?.status)
        assertNotNull(outboxes.items[firstRequest.messageId])

        repository.markChatRead(user)

        assertEquals(listOf(firstRequest, firstRequest), accounts.sent)
        assertTrue(outboxes.items.isEmpty())
    }

    @Test
    fun `delivered synchronization does not downgrade read messages`() = runTest {
        val sentId = UUID.randomUUID().toString()
        val readId = UUID.randomUUID().toString()
        messages.put(MessageEntity(sentId, "direct:${user.accountId}", "sent", true, 123L, "SENT"))
        messages.put(MessageEntity(readId, "direct:${user.accountId}", "read", true, 124L, "READ"))
        accounts.delivered = listOf(sentId, readId)

        repository.sync()

        assertEquals("DELIVERED", messages.byId(sentId)?.status)
        assertEquals("READ", messages.byId(readId)?.status)
    }

    private fun incomingMessage() = IncomingMessage(
        messageId = UUID.randomUUID().toString(),
        senderAccountId = user.accountId,
        senderDeviceId = UUID.randomUUID().toString(),
        ciphertextType = 2,
        ciphertext = Base64.getEncoder().encodeToString(byteArrayOf(4, 5, 6)),
        createdAt = 123L,
    )
}

private class FakeMessageCipher : MessageCipher {
    var onDecrypt: (IncomingMessage) -> ChatPayload = { error("No payload configured") }
    private val recipientDeviceId = UUID.randomUUID().toString()

    override suspend fun initializeDevice() = Unit

    override suspend fun encryptText(user: UserResponse, messageId: String, body: String): SendMessageRequest =
        SendMessageRequest(messageId, recipientDeviceId, 2, encode("ciphertext:$messageId"))

    override suspend fun encryptReadReceipt(user: UserResponse, originalMessageId: String): SendMessageRequest =
        SendMessageRequest(UUID.randomUUID().toString(), recipientDeviceId, 2, encode("receipt:$originalMessageId"))

    override suspend fun decrypt(message: IncomingMessage): ChatPayload = onDecrypt(message)

    override suspend fun safetyNumber(user: UserResponse): SafetyNumber {
        val identity = org.signal.libsignal.protocol.IdentityKeyPair.generate().publicKey
        return SafetyNumberService().create(
            SafetyNumberParty(user.accountId, recipientDeviceId, identity),
            SafetyNumberParty(user.accountId, recipientDeviceId, identity),
        )
    }

    override suspend fun acceptChangedIdentity(user: UserResponse) = Unit

    private fun encode(value: String): String = Base64.getEncoder().encodeToString(value.toByteArray())
}

private class FakeMessageAccounts(
    private val events: MutableList<String>,
) : MessageAccounts {
    val sent = mutableListOf<SendMessageRequest>()
    val acknowledged = mutableListOf<String>()
    var incoming = emptyList<IncomingMessage>()
    var delivered = emptyList<String>()
    var failNextSend = false
    var onSend: suspend (SendMessageRequest) -> Unit = {}

    override suspend fun sendMessage(request: SendMessageRequest): DeliveryResponse {
        events += "send:${request.messageId}"
        sent += request
        onSend(request)
        if (failNextSend) {
            failNextSend = false
            throw IOException("Offline")
        }
        return DeliveryResponse(request.messageId, false)
    }

    override suspend fun fetchMessages(): MessageListResponse = MessageListResponse(incoming)

    override suspend fun markDelivered(messageId: String) {
        events += "ack:$messageId"
        acknowledged += messageId
    }

    override suspend fun fetchDelivered(): DeliveredMessagesResponse = DeliveredMessagesResponse(delivered)
}

private class FakeMessageDao(
    private val events: MutableList<String>,
) : MessageDao {
    private val items = linkedMapOf<String, MessageEntity>()
    var rejectNextInsert = false

    fun put(message: MessageEntity) {
        items[message.messageId] = message
    }

    override suspend fun insert(message: MessageEntity): Long {
        events += "insert:${message.messageId}"
        if (rejectNextInsert) {
            rejectNextInsert = false
            return -1L
        }
        if (items.containsKey(message.messageId)) return -1L
        items[message.messageId] = message
        return 1L
    }

    override fun observeChat(chatId: String): Flow<List<MessageEntity>> =
        flowOf(items.values.filter { it.chatId == chatId }.sortedWith(compareBy(MessageEntity::createdAt, MessageEntity::messageId)))

    override suspend fun byId(messageId: String): MessageEntity? = items[messageId]

    override suspend fun unreadIncoming(chatId: String): List<MessageEntity> =
        items.values.filter { it.chatId == chatId && !it.outgoing && it.status == "DELIVERED" }

    override suspend fun markDelivered(messageId: String) {
        items[messageId]?.let { message ->
            if (message.status != "READ") items[messageId] = message.copy(status = "DELIVERED")
        }
    }

    override suspend fun updateStatusInChat(messageId: String, chatId: String, status: String) {
        items[messageId]?.takeIf { it.chatId == chatId }?.let { items[messageId] = it.copy(status = status) }
    }

    override suspend fun updateStatus(messageId: String, status: String) {
        items[messageId]?.let { items[messageId] = it.copy(status = status) }
    }
}

private class FakeOutboxDao(
    private val events: MutableList<String>,
) : OutboxDao {
    val items = linkedMapOf<String, OutboxEntity>()

    override suspend fun put(item: OutboxEntity) {
        events += "outbox:${item.messageId}"
        items[item.messageId] = item
    }

    override suspend fun all(): List<OutboxEntity> = items.values.sortedBy(OutboxEntity::createdAt)

    override suspend fun delete(messageId: String) {
        items.remove(messageId)
    }
}
