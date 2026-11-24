package dev.securemessenger.server.api

import dev.securemessenger.server.ApplicationDependencies
import dev.securemessenger.server.auth.AccountRecord
import dev.securemessenger.server.auth.AuthService
import dev.securemessenger.server.auth.FakeAuthRepository
import dev.securemessenger.server.auth.RecordingMailSender
import dev.securemessenger.server.configureApp
import dev.securemessenger.server.e2ee.E2eeRepository
import dev.securemessenger.server.e2ee.EventsHub
import dev.securemessenger.server.groups.GroupRepository
import dev.securemessenger.server.media.MediaChunk
import dev.securemessenger.server.media.MediaRepository
import dev.securemessenger.server.security.Argon2PasswordHasher
import dev.securemessenger.server.security.TokenService
import dev.securemessenger.shared.AddGroupMembersRequest
import dev.securemessenger.shared.CreateGroupRequest
import dev.securemessenger.shared.DeliveredMessagesResponse
import dev.securemessenger.shared.GroupMemberResponse
import dev.securemessenger.shared.GroupResponse
import dev.securemessenger.shared.IncomingMessage
import dev.securemessenger.shared.MediaStatusResponse
import dev.securemessenger.shared.MediaUploadRequest
import dev.securemessenger.shared.MediaUploadResponse
import dev.securemessenger.shared.PreKeyBundleResponse
import dev.securemessenger.shared.SendGroupMessageRequest
import dev.securemessenger.shared.SendMessageRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class GroupMediaApiTest {
    @Test
    fun `group and media routes require authentication`() = testApplication {
        val client = client(FakeGroupRepository(), FakeMediaRepository())

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/groups").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/media/${MEDIA_ID}/content").status)
    }

    @Test
    fun `group creation and fanout use the authenticated account`() = testApplication {
        val groups = FakeGroupRepository()
        val messages = FakeMessages()
        val client = client(groups, FakeMediaRepository(), messages)

        val response = client.post("/v1/groups") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(CreateGroupRequest(listOf("member_user")))
        }
        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals(GROUP_ID.toString(), response.body<GroupResponse>().groupId)

        val envelope = SendMessageRequest(UUID.randomUUID().toString(), DEVICE_ID.toString(), 3, encode(128))
        val groupMessageId = UUID.randomUUID()
        val sent = client.post("/v1/groups/$GROUP_ID/messages") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(SendGroupMessageRequest(groupMessageId.toString(), envelope))
        }
        assertEquals(HttpStatusCode.Accepted, sent.status)
        assertEquals(groupMessageId, messages.groupMessageId)
        assertEquals(envelope, messages.envelope)
    }

    @Test
    fun `media range is capped to the available ciphertext`() = testApplication {
        val client = client(FakeGroupRepository(), FakeMediaRepository())

        val response = client.get("/v1/media/$MEDIA_ID/content") {
            authenticate()
            header(HttpHeaders.Range, "bytes=4-100")
        }

        assertEquals(HttpStatusCode.PartialContent, response.status)
        assertEquals("bytes 4-31/32", response.headers[HttpHeaders.ContentRange])
        assertContentEquals(ByteArray(28) { (it + 4).toByte() }, response.body<ByteArray>())
    }

    private fun ApplicationTestBuilder.client(
        groups: GroupRepository,
        media: MediaRepository,
        messages: E2eeRepository = FakeMessages(),
    ): HttpClient {
        val now = Instant.now()
        val tokenService = TokenService(ByteArray(32) { 7 })
        val authRepository = FakeAuthRepository().apply {
            accounts[ACCOUNT_ID] = AccountRecord(ACCOUNT_ID, "owner@example.com", "owner_user", "unused", true)
        }
        authRepository.createSession(
            ACCOUNT_ID,
            DEVICE_ID,
            tokenService.hashToken(ACCESS_TOKEN),
            tokenService.hashToken("test-refresh-token"),
            now + Duration.ofMinutes(15),
            now + Duration.ofDays(30),
            now,
        )
        val auth = AuthService(authRepository, Argon2PasswordHasher(), tokenService, RecordingMailSender(), Clock.fixed(now, ZoneOffset.UTC))
        application {
            configureApp(
                ApplicationDependencies(
                    auth,
                    "a".repeat(48),
                    messages,
                    EventsHub(),
                    groups,
                    media,
                ),
            )
        }
        return createClient { install(ContentNegotiation) { json() } }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticate() {
        header(HttpHeaders.Authorization, "Bearer $ACCESS_TOKEN")
    }

    private class FakeGroupRepository : GroupRepository {
        override fun create(ownerAccountId: UUID, memberUsernames: List<String>) = response(ownerAccountId)
        override fun list(accountId: UUID): List<GroupResponse> = listOf(response(accountId))
        override fun getForMember(accountId: UUID, groupId: UUID): GroupResponse = response(accountId)
        override fun addMembers(ownerAccountId: UUID, groupId: UUID, memberUsernames: List<String>) = response(ownerAccountId)
        override fun removeMember(ownerAccountId: UUID, groupId: UUID, targetAccountId: UUID) = response(ownerAccountId)

        private fun response(owner: UUID) = GroupResponse(
            GROUP_ID.toString(),
            owner.toString(),
            listOf(GroupMemberResponse(owner.toString(), "owner_user", "OWNER")),
        )
    }

    private class FakeMediaRepository : MediaRepository {
        override fun create(ownerAccountId: UUID, ownerDeviceId: UUID, request: MediaUploadRequest) =
            MediaUploadResponse(MEDIA_ID.toString(), 1024, Long.MAX_VALUE)

        override fun status(accountId: UUID, deviceId: UUID, mediaId: UUID) =
            MediaStatusResponse(MEDIA_ID.toString(), 32, 32, true, Long.MAX_VALUE)

        override fun upload(accountId: UUID, deviceId: UUID, mediaId: UUID, offset: Long, bytes: ByteArray) =
            MediaStatusResponse(MEDIA_ID.toString(), offset + bytes.size, 32, false, Long.MAX_VALUE)

        override fun read(accountId: UUID, deviceId: UUID, mediaId: UUID, offset: Long, length: Int) =
            MediaChunk(ByteArray(length) { (it + offset.toInt()).toByte() }, 32)

        override fun delete(accountId: UUID, deviceId: UUID, mediaId: UUID) = Unit
        override fun deleteExpired(): Int = 0
    }

    private class FakeMessages : E2eeRepository {
        var groupMessageId: UUID? = null
        var envelope: SendMessageRequest? = null

        override fun enqueueGroup(senderAccountId: UUID, senderDeviceId: UUID, groupId: UUID, groupMessageId: UUID, request: SendMessageRequest) {
            this.groupMessageId = groupMessageId
            envelope = request
        }

        override fun enqueue(senderAccountId: UUID, senderDeviceId: UUID, request: SendMessageRequest) = Unit
        override fun fetchUndelivered(deviceId: UUID): List<IncomingMessage> = emptyList()
        override fun storeDeviceKeys(accountId: UUID, deviceId: UUID, upload: dev.securemessenger.shared.DeviceKeysUpload) = Unit
        override fun fetchBundleByUsername(username: String): PreKeyBundleResponse? = null
        override fun markDelivered(messageId: UUID, recipientDeviceId: UUID): Boolean = true
        override fun deliveredSenderDevice(messageId: UUID, recipientDeviceId: UUID): UUID? = null
        override fun deliveredForSender(senderDeviceId: UUID): List<String> = emptyList()
    }

    companion object {
        private val ACCOUNT_ID = UUID.randomUUID()
        private val DEVICE_ID = UUID.randomUUID()
        private val GROUP_ID = UUID.randomUUID()
        private val MEDIA_ID = UUID.randomUUID()
        private const val ACCESS_TOKEN = "test-access-token"

        private fun encode(size: Int): String = Base64.getEncoder().encodeToString(ByteArray(size) { 3 })
    }
}
