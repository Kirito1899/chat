package dev.securemessenger.data

import dev.securemessenger.data.local.SessionStore
import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.data.remote.MessengerApi
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: FakeSessionStore
    private lateinit var repository: AuthRepository
    private val json = Json { ignoreUnknownKeys = false }

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        sessions = FakeSessionStore()
        repository = AuthRepository(MessengerApi(server.url("/").toString(), OkHttpClient()), sessions)
    }

    @AfterTest
    fun tearDown() {
        server.close()
    }

    @Test
    fun `login stores tokens and stable device id`() = runTest {
        val tokens = tokens("access-1", "refresh-1")
        server.enqueue(jsonResponse(json.encodeToString(tokens)))

        val result = repository.login("user@example.com", "password-12345")

        assertEquals(tokens, result)
        assertEquals(tokens, sessions.stored)
        val request = server.takeRequest()
        assertTrue(request.url.encodedPath.endsWith("/v1/auth/login"))
        assertFalse(request.url.queryParameterNames.any { "password" in it || "token" in it })
    }

    @Test
    fun `search refreshes once on 401 and retries with rotated access token`() = runTest {
        sessions.stored = tokens("expired-access", "refresh-old")
        val rotated = tokens("access-new", "refresh-new")
        val user = UserResponse(UUID.randomUUID().toString(), "test_user")
        server.enqueue(jsonResponse("""{"code":"INVALID_CREDENTIALS"}""", 401))
        server.enqueue(jsonResponse(json.encodeToString(rotated)))
        server.enqueue(jsonResponse(json.encodeToString(user)))

        val result = repository.searchByUsername("test_user")

        assertEquals(user, result)
        assertEquals(rotated, sessions.stored)
        server.takeRequest()
        val refresh = server.takeRequest()
        assertTrue(refresh.url.encodedPath.endsWith("/v1/auth/refresh"))
        val retry = server.takeRequest()
        assertEquals("Bearer access-new", retry.headers["Authorization"])
    }

    @Test
    fun `failed login does not overwrite existing session`() = runTest {
        val existing = tokens("access-existing", "refresh-existing")
        sessions.stored = existing
        server.enqueue(jsonResponse("""{"code":"INVALID_CREDENTIALS"}""", 401))

        assertFailsWith<ApiException> { repository.login("user@example.com", "wrong-password-1") }
        assertEquals(existing, sessions.stored)
    }

    @Test
    fun `logout clears local session even if server is unavailable`() = runTest {
        sessions.stored = tokens("access", "refresh")
        server.enqueue(MockResponse.Builder().code(500).build())

        repository.logout()

        assertNull(sessions.stored)
    }

    private fun tokens(access: String, refresh: String) = TokenResponse(
        accessToken = access,
        refreshToken = refresh,
        accessExpiresAt = 1_000_000L,
        refreshExpiresAt = 2_000_000L,
        accountId = UUID.randomUUID().toString(),
        deviceId = sessions.deviceId.toString(),
        username = "test_user",
    )

    private fun jsonResponse(body: String, status: Int = 200): MockResponse = MockResponse.Builder()
        .code(status)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()
}

private class FakeSessionStore : SessionStore {
    var stored: TokenResponse? = null
    val deviceId: UUID = UUID.randomUUID()

    override suspend fun read(): TokenResponse? = stored

    override suspend fun save(session: TokenResponse) {
        stored = session
    }

    override suspend fun clear() {
        stored = null
    }

    override suspend fun deviceId(): String = deviceId.toString()
}
