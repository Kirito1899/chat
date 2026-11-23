package dev.securemessenger.server.api

import dev.securemessenger.server.ApplicationDependencies
import dev.securemessenger.server.auth.AuthService
import dev.securemessenger.server.auth.FakeAuthRepository
import dev.securemessenger.server.auth.RecordingMailSender
import dev.securemessenger.server.configureApp
import dev.securemessenger.server.security.Argon2PasswordHasher
import dev.securemessenger.server.security.TokenService
import dev.securemessenger.shared.EmailCodeRequest
import dev.securemessenger.shared.InviteResponse
import dev.securemessenger.shared.LoginRequest
import dev.securemessenger.shared.RecoveryRequest
import dev.securemessenger.shared.RegisterRequest
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiTest {
    @Test
    fun `complete account api flow protects private routes`() = testApplication {
        val repository = FakeAuthRepository()
        val mail = RecordingMailSender()
        val auth = AuthService(
            repository,
            Argon2PasswordHasher(),
            TokenService(ByteArray(32) { 8 }),
            mail,
            Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC),
        )
        application {
            configureApp(ApplicationDependencies(auth, "a" .repeat(48)))
        }
        val client = jsonClient()

        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/admin/invites").status)
        val inviteResponse = client.post("/v1/admin/invites") {
            header("X-Admin-Token", "a".repeat(48))
        }
        assertEquals(HttpStatusCode.Created, inviteResponse.status)
        val invite = inviteResponse.body<InviteResponse>().inviteCode

        val register = client.post("/v1/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest(invite, "api@example.com", "api_user", "api-password-123"))
        }
        assertEquals(HttpStatusCode.Accepted, register.status)
        val code = mail.messages.single().code
        assertEquals(HttpStatusCode.NoContent, client.post("/v1/auth/verify-email") {
            contentType(ContentType.Application.Json)
            setBody(EmailCodeRequest("api@example.com", code))
        }.status)

        val login = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest("api@example.com", "api-password-123", UUID.randomUUID().toString()))
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val tokens = login.body<TokenResponse>()

        assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/users/by-username/api_user").status)
        val found = client.get("/v1/users/by-username/api_user") {
            header("Authorization", "Bearer ${tokens.accessToken}")
        }
        assertEquals(HttpStatusCode.OK, found.status)
        assertEquals(UserResponse(tokens.accountId, "api_user"), found.body<UserResponse>())

        assertEquals(HttpStatusCode.Accepted, client.post("/v1/auth/recovery") {
            contentType(ContentType.Application.Json)
            setBody(RecoveryRequest("unknown@example.com"))
        }.status)
    }

    @Test
    fun `malformed json never becomes a server error`() = testApplication {
        val repository = FakeAuthRepository()
        val auth = AuthService(
            repository,
            Argon2PasswordHasher(),
            TokenService(ByteArray(32) { 8 }),
            RecordingMailSender(),
            Clock.systemUTC(),
        )
        application { configureApp(ApplicationDependencies(auth, "a".repeat(48))) }
        val response = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("{broken")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val oversized = client.post("/v1/auth/login") {
            contentType(ContentType.Application.Json)
            setBody("x".repeat(32 * 1024 + 1))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, oversized.status)
    }

    private fun io.ktor.server.testing.ApplicationTestBuilder.jsonClient(): HttpClient = createClient {
        install(ContentNegotiation) { json() }
    }
}
