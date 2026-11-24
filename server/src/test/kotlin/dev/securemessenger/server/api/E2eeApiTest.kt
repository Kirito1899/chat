package dev.securemessenger.server.api

import dev.securemessenger.server.ApplicationDependencies
import dev.securemessenger.server.auth.AccountRecord
import dev.securemessenger.server.auth.AuthService
import dev.securemessenger.server.auth.FakeAuthRepository
import dev.securemessenger.server.auth.RecordingMailSender
import dev.securemessenger.server.configureApp
import dev.securemessenger.server.e2ee.E2eeRepository
import dev.securemessenger.server.e2ee.EventsHub
import dev.securemessenger.server.e2ee.MessageAuthorizationException
import dev.securemessenger.server.security.Argon2PasswordHasher
import dev.securemessenger.server.security.TokenService
import dev.securemessenger.shared.DeliveredMessagesResponse
import dev.securemessenger.shared.DeliveryResponse
import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.ErrorResponse
import dev.securemessenger.shared.IncomingMessage
import dev.securemessenger.shared.KyberPreKeyDto
import dev.securemessenger.shared.MessageListResponse
import dev.securemessenger.shared.PreKeyBundleResponse
import dev.securemessenger.shared.PreKeyDto
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.SignedPreKeyDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class E2eeApiTest {
    @Test
    fun `e2ee routes require authentication`() = testApplication {
        val client = e2eeClient(FakeE2eeRepository())

        assertEquals(HttpStatusCode.Unauthorized, client.put("/v1/devices/keys").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/prekey-bundles/by-username/sender_user").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/messages").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/messages").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/messages/delivered").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/messages/${UUID.randomUUID()}/delivered").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/events").status)
    }

    @Test
    fun `authorized routes preserve wire contracts`() = testApplication {
        val repository = FakeE2eeRepository()
        repository.bundle = preKeyBundle()
        repository.messages = listOf(
            IncomingMessage(
                UUID.randomUUID().toString(),
                ACCOUNT_ID.toString(),
                DEVICE_ID.toString(),
                3,
                encode(32, 7),
                1_700_000_000_000,
            ),
        )
        repository.deliveryRecipient = DEVICE_ID
        repository.senderOnDelivery = DEVICE_ID
        repository.deliveredIds = listOf(repository.messages.single().messageId)
        val client = e2eeClient(repository)
        val upload = deviceKeysUpload()
        val messageId = UUID.randomUUID()

        val uploadResponse = client.put("/v1/devices/keys") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(upload)
        }
        assertEquals(HttpStatusCode.NoContent, uploadResponse.status)
        assertEquals(Triple(ACCOUNT_ID, DEVICE_ID, upload), repository.uploads.single())

        val bundleResponse = client.get("/v1/prekey-bundles/by-username/@Sender_User") {
            authenticate()
        }
        assertEquals(HttpStatusCode.OK, bundleResponse.status)
        assertEquals(repository.bundle, bundleResponse.body<PreKeyBundleResponse>())

        val sendResponse = client.post("/v1/messages") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(messageRequest(messageId = messageId))
        }
        assertEquals(HttpStatusCode.Accepted, sendResponse.status)
        assertEquals(DeliveryResponse(messageId.toString(), false), sendResponse.body())
        assertEquals(messageId.toString(), repository.requests.single().third.messageId)

        val messagesResponse = client.get("/v1/messages") { authenticate() }
        assertEquals(HttpStatusCode.OK, messagesResponse.status)
        assertEquals(MessageListResponse(repository.messages), messagesResponse.body())

        val deliveredResponse = client.post("/v1/messages/${repository.messages.single().messageId}/delivered") {
            authenticate()
        }
        assertEquals(HttpStatusCode.OK, deliveredResponse.status)
        assertEquals(
            DeliveryResponse(repository.messages.single().messageId, true),
            deliveredResponse.body(),
        )

        val deliveredIdsResponse = client.get("/v1/messages/delivered") { authenticate() }
        assertEquals(HttpStatusCode.OK, deliveredIdsResponse.status)
        assertEquals(DeliveredMessagesResponse(repository.deliveredIds), deliveredIdsResponse.body())
    }

    @Test
    fun `malformed delivery id is a client error`() = testApplication {
        val client = e2eeClient(FakeE2eeRepository())

        val response = client.post("/v1/messages/not-a-uuid/delivered") {
            authenticate()
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorResponse("INVALID_UUID"), response.body())
    }

    @Test
    fun `malformed key upload is rejected`() = testApplication {
        val client = e2eeClient(FakeE2eeRepository())

        val response = client.put("/v1/devices/keys") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(deviceKeysUpload().copy(identityKey = "not base64"))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(ErrorResponse("INVALID_BASE64"), response.body())
    }

    @Test
    fun `foreign delivery acknowledgement is forbidden`() = testApplication {
        val repository = FakeE2eeRepository()
        repository.deliveryRecipient = UUID.randomUUID()
        val client = e2eeClient(repository)

        val response = client.post("/v1/messages/${UUID.randomUUID()}/delivered") {
            authenticate()
        }

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertEquals(ErrorResponse("MESSAGE_FORBIDDEN"), response.body())
    }

    @Test
    fun `maximum validated ciphertext fits the message request limit`() = testApplication {
        val client = e2eeClient(FakeE2eeRepository())

        val response = client.post("/v1/messages") {
            authenticate()
            contentType(ContentType.Application.Json)
            setBody(messageRequest(ciphertext = encode(32 * 1024, 0)))
        }

        assertEquals(HttpStatusCode.Accepted, response.status)
    }

    @Test
    fun `wakeups contain only generic event types`() = testApplication {
        val repository = FakeE2eeRepository()
        repository.deliveryRecipient = DEVICE_ID
        repository.senderOnDelivery = DEVICE_ID
        val client = e2eeClient(repository)

        client.webSocket(
            urlString = "/v1/events",
            request = { authenticate() },
        ) {
            val sendResponse = client.post("/v1/messages") {
                authenticate()
                contentType(ContentType.Application.Json)
                setBody(messageRequest(recipientDeviceId = DEVICE_ID))
            }
            assertEquals(HttpStatusCode.Accepted, sendResponse.status)
            val sync = incoming.receive()
            assertIs<Frame.Text>(sync)
            assertEquals("{\"event\":\"SYNC\"}", sync.readText())

            val messageId = UUID.randomUUID()
            val deliveredResponse = client.post("/v1/messages/$messageId/delivered") {
                authenticate()
            }
            assertEquals(HttpStatusCode.OK, deliveredResponse.status)
            val delivered = incoming.receive()
            assertIs<Frame.Text>(delivered)
            assertEquals("{\"event\":\"DELIVERED\"}", delivered.readText())
        }
    }

    private fun ApplicationTestBuilder.e2eeClient(repository: E2eeRepository): HttpClient {
        val authRepository = FakeAuthRepository()
        val tokenService = TokenService(ByteArray(32) { 3 })
        val now = Instant.parse("2026-11-23T12:00:00Z")
        authRepository.accounts[ACCOUNT_ID] = AccountRecord(
            ACCOUNT_ID,
            "sender@example.com",
            "sender_user",
            "unused",
            true,
        )
        authRepository.createSession(
            ACCOUNT_ID,
            DEVICE_ID,
            tokenService.hashToken(ACCESS_TOKEN),
            tokenService.hashToken("test-refresh-token"),
            now + Duration.ofMinutes(15),
            now + Duration.ofDays(30),
            now,
        )
        val auth = AuthService(
            authRepository,
            Argon2PasswordHasher(),
            tokenService,
            RecordingMailSender(),
            Clock.fixed(now, ZoneOffset.UTC),
        )
        application {
            configureApp(ApplicationDependencies(auth, "a".repeat(48), repository, EventsHub()))
        }
        return createClient {
            install(ContentNegotiation) { json() }
            install(WebSockets)
        }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.authenticate() {
        header(HttpHeaders.Authorization, "Bearer $ACCESS_TOKEN")
    }

    private class FakeE2eeRepository : E2eeRepository {
        val uploads = mutableListOf<Triple<UUID, UUID, DeviceKeysUpload>>()
        val requests = mutableListOf<Triple<UUID, UUID, SendMessageRequest>>()
        var bundle: PreKeyBundleResponse? = null
        var messages: List<IncomingMessage> = emptyList()
        var deliveredIds: List<String> = emptyList()
        var deliveryRecipient: UUID? = null
        var senderOnDelivery: UUID? = null

        override fun storeDeviceKeys(accountId: UUID, deviceId: UUID, upload: DeviceKeysUpload) {
            upload.validate()
            uploads += Triple(accountId, deviceId, upload)
        }

        override fun fetchBundleByUsername(username: String): PreKeyBundleResponse? = bundle

        override fun enqueue(senderAccountId: UUID, senderDeviceId: UUID, request: SendMessageRequest) {
            request.validate()
            requests += Triple(senderAccountId, senderDeviceId, request)
        }

        override fun enqueueGroup(senderAccountId: UUID, senderDeviceId: UUID, groupId: UUID, groupMessageId: UUID, request: SendMessageRequest) {
            request.validate()
            requests += Triple(senderAccountId, senderDeviceId, request)
        }

        override fun fetchUndelivered(deviceId: UUID): List<IncomingMessage> = messages

        override fun markDelivered(messageId: UUID, recipientDeviceId: UUID): Boolean {
            if (deliveryRecipient != null && deliveryRecipient != recipientDeviceId) {
                throw MessageAuthorizationException()
            }
            return true
        }

        override fun deliveredSenderDevice(messageId: UUID, recipientDeviceId: UUID): UUID? = senderOnDelivery
        override fun deliveredForSender(senderDeviceId: UUID): List<String> = deliveredIds
    }

    companion object {
        private val ACCOUNT_ID = UUID.randomUUID()
        private val DEVICE_ID = UUID.randomUUID()
        private const val ACCESS_TOKEN = "test-access-token"

        private fun deviceKeysUpload() = DeviceKeysUpload(
            registrationId = 123,
            identityKey = encode(33, 1),
            signedPreKey = SignedPreKeyDto(1, encode(33, 1), encode(64, 4)),
            preKeys = listOf(PreKeyDto(2, encode(33, 1))),
            kyberPreKeys = listOf(KyberPreKeyDto(3, encode(1569, 2), encode(64, 4))),
            lastResortKyberPreKey = KyberPreKeyDto(4, encode(1569, 2), encode(64, 4)),
        )

        private fun preKeyBundle() = PreKeyBundleResponse(
            accountId = ACCOUNT_ID.toString(),
            deviceId = DEVICE_ID.toString(),
            registrationId = 123,
            identityKey = encode(33, 1),
            signedPreKey = SignedPreKeyDto(1, encode(33, 1), encode(64, 4)),
            preKey = PreKeyDto(2, encode(33, 1)),
            kyberPreKey = KyberPreKeyDto(3, encode(1569, 2), encode(64, 4)),
            remainingPreKeys = 1,
        )

        private fun messageRequest(
            messageId: UUID = UUID.randomUUID(),
            recipientDeviceId: UUID = UUID.randomUUID(),
            ciphertext: String = encode(32, 7),
        ) = SendMessageRequest(messageId.toString(), recipientDeviceId.toString(), 3, ciphertext)

        private fun encode(size: Int, value: Int): String =
            Base64.getEncoder().encodeToString(ByteArray(size) { value.toByte() })
    }
}
