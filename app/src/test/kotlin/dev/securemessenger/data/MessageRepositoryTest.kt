package dev.securemessenger.data

import dev.securemessenger.data.e2ee.ChatPayload
import dev.securemessenger.data.e2ee.DuplicateEnvelopeException
import dev.securemessenger.data.e2ee.SafetyNumber
import dev.securemessenger.data.e2ee.SafetyNumberParty
import dev.securemessenger.data.e2ee.SafetyNumberService
import dev.securemessenger.data.local.GroupInfoDao
import dev.securemessenger.data.local.GroupInfoEntity
import dev.securemessenger.data.local.GroupReceiptDao
import dev.securemessenger.data.local.GroupReceiptEntity
import dev.securemessenger.data.local.MessageDao
import dev.securemessenger.data.local.MessageEntity
import dev.securemessenger.data.local.OutboxDao
import dev.securemessenger.data.local.OutboxEntity
import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.shared.GroupMemberResponse
import dev.securemessenger.shared.GroupResponse
import dev.securemessenger.shared.SendGroupMessageRequest
import dev.securemessenger.shared.DeliveredMessagesResponse
import dev.securemessenger.shared.DeliveryResponse
import dev.securemessenger.shared.IncomingMessage
import dev.securemessenger.shared.MessageListResponse
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.UserResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

        val newMessages = repository.sync()

        assertEquals(1, newMessages)
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

        assertEquals(0, repository.sync())

        assertEquals(listOf(incoming.messageId), accounts.acknowledged)
    }

    @Test
    fun `matching read receipt marks outgoing message read`() = runTest {
        val originalId = UUID.randomUUID().toString()
        messages.put(MessageEntity(originalId, "direct:${user.accountId}", "outgoing", true, 123L, "SENT"))
        val incoming = incomingMessage()
        accounts.incoming = listOf(incoming)
        cipher.onDecrypt = { ChatPayload(ChatPayload.READ_RECEIPT, originalId, createdAt = 124L) }

        assertEquals(0, repository.sync())

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

    @Test
    fun `group fanout aggregates delivery and read receipts per recipient`() = runTest {
        val owner = UUID.randomUUID().toString()
        val first = GroupMemberResponse(UUID.randomUUID().toString(), "first", "MEMBER")
        val second = GroupMemberResponse(UUID.randomUUID().toString(), "second", "MEMBER")
        val group = GroupResponse(UUID.randomUUID().toString(), owner, listOf(GroupMemberResponse(owner, "owner", "OWNER"), first, second))
        val devices = mapOf(first.accountId to UUID.randomUUID().toString(), second.accountId to UUID.randomUUID().toString())
        accounts.localAccountId = owner
        accounts.group = group
        cipher.deviceForAccount = { devices.getValue(it) }
        val receipts = FakeGroupReceiptDao()
        repository = MessageRepository(messages, outboxes, cipher, accounts, json, FakeGroupInfoDao(), receipts) { block -> block() }

        val messageId = repository.sendGroupText(group, "group secret")

        assertEquals(2, accounts.groupSent.size)
        assertEquals(2, accounts.groupSent.map { it.envelope.messageId }.toSet().size)
        assertTrue(accounts.groupSent.all { it.groupMessageId == messageId })
        assertTrue(outboxes.items.isEmpty())
        assertEquals("SENT", repository.observeGroupMessages(group.groupId).first().single().status)

        accounts.delivered = listOf(accounts.groupSent[0].envelope.messageId)
        repository.sync()
        assertEquals("SENT", repository.observeGroupMessages(group.groupId).first().single().status)
        accounts.delivered = accounts.groupSent.map { it.envelope.messageId }
        repository.sync()
        assertEquals("DELIVERED", repository.observeGroupMessages(group.groupId).first().single().status)

        suspend fun readFrom(member: GroupMemberResponse) {
            val payload = ChatPayload(ChatPayload.GROUP_READ_RECEIPT, messageId, createdAt = 1, groupId = group.groupId)
            cipher.onDecrypt = { payload }
            accounts.incoming = listOf(
                IncomingMessage(
                    UUID.randomUUID().toString(),
                    member.accountId,
                    devices.getValue(member.accountId),
                    2,
                    "ciphertext",
                    1,
                    group.groupId,
                    payload.messageId,
                ),
            )
            repository.sync()
            accounts.incoming = emptyList()
        }
        readFrom(first)
        messages.put(MessageEntity("incoming", "group:${group.groupId}", "incoming", false, 1, "DELIVERED", senderAccountId = first.accountId))
        repository.markGroupRead(group)
        assertNotEquals(messageId, accounts.groupSent.last().groupMessageId)
        assertEquals("DELIVERED", repository.observeGroupMessages(group.groupId).first().single { it.outgoing }.status)
        readFrom(second)
        assertEquals("READ", repository.observeGroupMessages(group.groupId).first().single { it.outgoing }.status)
    }

    @Test
    fun `removed group recipient is skipped without blocking other recipients`() = runTest {
        val owner = UUID.randomUUID().toString()
        val first = GroupMemberResponse(UUID.randomUUID().toString(), "first", "MEMBER")
        val removed = GroupMemberResponse(UUID.randomUUID().toString(), "removed", "MEMBER")
        val group = GroupResponse(UUID.randomUUID().toString(), owner, listOf(GroupMemberResponse(owner, "owner", "OWNER"), first, removed))
        val removedDevice = UUID.randomUUID().toString()
        accounts.localAccountId = owner
        cipher.deviceForAccount = { account -> if (account == removed.accountId) removedDevice else UUID.randomUUID().toString() }
        accounts.onGroupSend = { _, request ->
            if (request.envelope.recipientDeviceId == removedDevice) throw ApiException("RECIPIENT_NOT_FOUND", 404)
        }
        val receipts = FakeGroupReceiptDao()
        repository = MessageRepository(messages, outboxes, cipher, accounts, json, FakeGroupInfoDao(), receipts) { block -> block() }

        repository.sendGroupText(group, "still delivered")

        assertTrue(outboxes.items.isEmpty())
        assertEquals("SKIPPED", receipts.items.values.single { it.recipientDeviceId == removedDevice }.state)
        assertEquals("SENT", repository.observeGroupMessages(group.groupId).first().single().status)
    }

    @Test
    fun `stale conflicting outbox does not block other recipients`() = runTest {
        val owner = UUID.randomUUID().toString()
        val first = GroupMemberResponse(UUID.randomUUID().toString(), "first", "MEMBER")
        val stale = GroupMemberResponse(UUID.randomUUID().toString(), "stale", "MEMBER")
        val group = GroupResponse(UUID.randomUUID().toString(), owner, listOf(GroupMemberResponse(owner, "owner", "OWNER"), first, stale))
        val staleDevice = UUID.randomUUID().toString()
        accounts.localAccountId = owner
        cipher.deviceForAccount = { account -> if (account == stale.accountId) staleDevice else UUID.randomUUID().toString() }
        accounts.onGroupSend = { _, request ->
            if (request.envelope.recipientDeviceId == staleDevice) throw ApiException("MESSAGE_CONFLICT", 409)
        }
        val receipts = FakeGroupReceiptDao()
        repository = MessageRepository(messages, outboxes, cipher, accounts, json, FakeGroupInfoDao(), receipts) { block -> block() }

        repository.sendGroupText(group, "new valid message")

        assertTrue(outboxes.items.isEmpty())
        assertEquals("SKIPPED", receipts.items.values.single { it.recipientDeviceId == staleDevice }.state)
        assertEquals("SENT", repository.observeGroupMessages(group.groupId).first().single().status)
    }

    @Test
    fun `concurrent group reads emit only one receipt`() = runTest {
        val owner = UUID.randomUUID().toString()
        val group = GroupResponse(
            UUID.randomUUID().toString(),
            owner,
            listOf(GroupMemberResponse(owner, "owner", "OWNER"), GroupMemberResponse(user.accountId, user.username, "MEMBER")),
        )
        messages.put(MessageEntity("incoming", "group:${group.groupId}", "unread", false, 1, "DELIVERED", senderAccountId = user.accountId))
        accounts.localAccountId = owner
        cipher.beforeEncryptPayload = { yield() }
        repository = MessageRepository(messages, outboxes, cipher, accounts, json, FakeGroupInfoDao(), FakeGroupReceiptDao()) { block -> block() }

        val firstRead = launch { repository.markGroupRead(group) }
        val secondRead = launch { repository.markGroupRead(group) }
        firstRead.join()
        secondRead.join()

        assertEquals(1, accounts.groupSent.size)
        assertEquals("READ", messages.byId("incoming")?.status)
        assertTrue(outboxes.items.isEmpty())
    }

    @Test
    fun `group info is accepted only from the current owner`() = runTest {
        val owner = UUID.randomUUID().toString()
        val member = GroupMemberResponse(UUID.randomUUID().toString(), "member", "MEMBER")
        val group = GroupResponse(UUID.randomUUID().toString(), owner, listOf(GroupMemberResponse(owner, "owner", "OWNER"), member))
        accounts.group = group
        accounts.localAccountId = owner
        val info = FakeGroupInfoDao()
        repository = MessageRepository(messages, outboxes, cipher, accounts, json, info, FakeGroupReceiptDao()) { block -> block() }

        suspend fun receiveTitle(sender: String, title: String) {
            val payload = ChatPayload(ChatPayload.GROUP_INFO, UUID.randomUUID().toString(), title, 1, group.groupId)
            cipher.onDecrypt = { payload }
            accounts.incoming = listOf(
                IncomingMessage(UUID.randomUUID().toString(), sender, UUID.randomUUID().toString(), 2, "ciphertext", 1, group.groupId, payload.messageId),
            )
            assertEquals(0, repository.sync())
            accounts.incoming = emptyList()
        }
        receiveTitle(owner, "Private group")
        assertEquals("Private group", info.get(group.groupId)?.title)
        receiveTitle(member.accountId, "Forged title")
        assertEquals("Private group", info.get(group.groupId)?.title)
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

    var deviceForAccount: (String) -> String = { recipientDeviceId }
    var beforeEncryptPayload: suspend () -> Unit = {}

    override suspend fun encryptReadReceipt(user: UserResponse, originalMessageId: String): SendMessageRequest =
        SendMessageRequest(UUID.randomUUID().toString(), recipientDeviceId, 2, encode("receipt:$originalMessageId"))

    override suspend fun encryptDirectPayload(user: UserResponse, payload: ChatPayload): SendMessageRequest =
        SendMessageRequest(payload.messageId, deviceForAccount(user.accountId), 2, encode("payload:${payload.messageId}"))

    override suspend fun encryptPayload(user: UserResponse, payload: ChatPayload): SendMessageRequest {
        beforeEncryptPayload()
        return SendMessageRequest(UUID.randomUUID().toString(), deviceForAccount(user.accountId), 2, encode("payload:${payload.messageId}"))
    }

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
    val groupSent = mutableListOf<SendGroupMessageRequest>()
    var onGroupSend: suspend (String, SendGroupMessageRequest) -> Unit = { _, _ -> }
    var localAccountId: String? = null
    var group: GroupResponse? = null

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

    override suspend fun sendGroupMessage(groupId: String, request: SendGroupMessageRequest): DeliveryResponse {
        onGroupSend(groupId, request)
        groupSent += request
        return DeliveryResponse(request.envelope.messageId, false)
    }

    override suspend fun currentAccountId(): String? = localAccountId

    override suspend fun fetchGroup(groupId: String): GroupResponse = requireNotNull(group)
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

    override suspend fun byIdInChat(messageId: String, chatId: String): MessageEntity? =
        items[messageId]?.takeIf { it.chatId == chatId }

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
        items[item.envelopeId] = item
    }

    override suspend fun all(): List<OutboxEntity> = items.values.sortedBy(OutboxEntity::createdAt)

    override suspend fun delete(envelopeId: String) {
        items.remove(envelopeId)
    }
}


private class FakeGroupInfoDao : GroupInfoDao {
    private val values = mutableMapOf<String, GroupInfoEntity>()

    override suspend fun upsert(info: GroupInfoEntity) {
        values[info.groupId] = info
    }

    override suspend fun get(groupId: String): GroupInfoEntity? = values[groupId]

    override fun observeAll(): Flow<List<GroupInfoEntity>> = flowOf(values.values.toList())
}

private class FakeGroupReceiptDao : GroupReceiptDao {
    val items = linkedMapOf<String, GroupReceiptEntity>()
    private val flow = MutableStateFlow<List<GroupReceiptEntity>>(emptyList())

    override suspend fun insert(item: GroupReceiptEntity): Long {
        val inserted = if (item.envelopeId in items) -1L else 1L
        if (inserted == 1L) {
            items[item.envelopeId] = item
            update()
        }
        return inserted
    }

    override suspend fun markSent(envelopeId: String) {
        items[envelopeId]?.takeIf { it.state == "PENDING" }?.let { items[envelopeId] = it.copy(state = "SENT") }
        update()
    }

    override suspend fun markDelivered(envelopeId: String) {
        items[envelopeId]?.takeIf { it.state == "PENDING" || it.state == "SENT" }?.let {
            items[envelopeId] = it.copy(state = "DELIVERED")
        }
        update()
    }

    override suspend fun markSkipped(envelopeId: String) {
        items[envelopeId]?.takeIf { it.state != "READ" }?.let { items[envelopeId] = it.copy(state = "SKIPPED") }
        update()
    }

    override suspend fun markRead(groupMessageId: String, recipientDeviceId: String) {
        items.values.filter { it.groupMessageId == groupMessageId && it.recipientDeviceId == recipientDeviceId }.forEach {
            items[it.envelopeId] = it.copy(state = "READ")
        }
        update()
    }

    override fun observeAll(): Flow<List<GroupReceiptEntity>> = flow

    private fun update() {
        flow.value = items.values.toList()
    }
}
