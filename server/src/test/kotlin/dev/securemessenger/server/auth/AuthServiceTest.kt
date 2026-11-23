package dev.securemessenger.server.auth

import dev.securemessenger.server.mail.MailPurpose
import dev.securemessenger.server.security.Argon2PasswordHasher
import dev.securemessenger.server.security.TokenService
import dev.securemessenger.shared.LoginRequest
import dev.securemessenger.shared.PasswordResetRequest
import dev.securemessenger.shared.RefreshRequest
import dev.securemessenger.shared.RegisterRequest
import dev.securemessenger.shared.EmailCodeRequest
import dev.securemessenger.shared.RecoveryRequest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var repository: FakeAuthRepository
    private lateinit var mail: RecordingMailSender
    private lateinit var service: AuthService
    private val deviceId = UUID.randomUUID()

    @BeforeTest
    fun setUp() {
        repository = FakeAuthRepository()
        mail = RecordingMailSender()
        service = AuthService(
            repository,
            Argon2PasswordHasher(),
            TokenService(ByteArray(32) { 4 }),
            mail,
            clock,
        )
    }

    @Test
    fun `register verify login and search use real workflow`() {
        val invite = service.createInvite()
        val registration = register(invite)
        val code = mail.messages.single { it.purpose == MailPurpose.EMAIL_VERIFICATION }.code
        assertFailsWith<AuthException> { login("initial-password-1") }
        service.verifyEmail(EmailCodeRequest(registration.email, code))
        val tokens = login("initial-password-1")
        assertEquals("test_user", tokens.username)
        assertEquals("test_user", service.findByUsername("@test_user")?.username)
    }

    @Test
    fun `invite cannot be consumed twice`() {
        val invite = service.createInvite()
        register(invite)
        assertFailsWith<AuthException> {
            register(invite, email = "second@example.com", username = "second_user")
        }
    }

    @Test
    fun `sixth wrong verification code cannot succeed`() {
        val invite = service.createInvite()
        val registration = register(invite)
        val correct = mail.messages.single().code
        repeat(5) {
            assertFailsWith<AuthException> {
                service.verifyEmail(EmailCodeRequest(registration.email, "00000000"))
            }
        }
        assertFailsWith<AuthException> { service.verifyEmail(EmailCodeRequest(registration.email, correct)) }
    }

    @Test
    fun `unknown recovery email has identical accepted result and sends nothing`() {
        service.recover(RecoveryRequest("missing@example.com"))
        assertTrue(mail.messages.isEmpty())
    }

    @Test
    fun `password reset revokes old session and refresh reuse is detected`() {
        val invite = service.createInvite()
        val registration = register(invite)
        service.verifyEmail(EmailCodeRequest(registration.email, mail.messages.single().code))
        val initial = login("initial-password-1")
        val rotated = service.refresh(RefreshRequest(initial.refreshToken))
        assertNotEquals(initial.refreshToken, rotated.refreshToken)
        assertFailsWith<AuthException> { service.refresh(RefreshRequest(initial.refreshToken)) }
        assertNull(service.authenticateAccess(rotated.accessToken))

        service.recover(RecoveryRequest(registration.email))
        val recovery = mail.messages.single { it.purpose == MailPurpose.PASSWORD_RECOVERY }.code
        service.resetPassword(PasswordResetRequest(registration.email, recovery, "new-password-123"))
        assertFailsWith<AuthException> { login("initial-password-1") }
        login("new-password-123")
    }

    @Test
    fun `second active device is rejected`() {
        val invite = service.createInvite()
        val registration = register(invite)
        service.verifyEmail(EmailCodeRequest(registration.email, mail.messages.single().code))
        login("initial-password-1")
        assertFailsWith<AuthException> {
            service.login(LoginRequest(registration.email, "initial-password-1", UUID.randomUUID().toString()))
        }
    }

    private fun register(
        invite: String,
        email: String = "test@example.com",
        username: String = "test_user",
    ) = service.register(RegisterRequest(invite, email, username, "initial-password-1"))

    private fun login(password: String) = service.login(
        LoginRequest("test@example.com", password, deviceId.toString()),
    )
}
