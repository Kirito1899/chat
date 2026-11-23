package dev.securemessenger.server.auth

import dev.securemessenger.server.mail.MailPurpose
import dev.securemessenger.server.mail.MailSender
import dev.securemessenger.server.security.PasswordHasher
import dev.securemessenger.server.security.TokenService
import dev.securemessenger.server.storage.AuthRepository
import dev.securemessenger.shared.EmailCodeRequest
import dev.securemessenger.shared.LoginRequest
import dev.securemessenger.shared.LogoutRequest
import dev.securemessenger.shared.PasswordError
import dev.securemessenger.shared.PasswordPolicy
import dev.securemessenger.shared.PasswordResetRequest
import dev.securemessenger.shared.RecoveryRequest
import dev.securemessenger.shared.RefreshRequest
import dev.securemessenger.shared.RegisterRequest
import dev.securemessenger.shared.RegisterResponse
import dev.securemessenger.shared.ResendVerificationRequest
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse
import dev.securemessenger.shared.ValidationException
import dev.securemessenger.shared.EmailNormalizer
import dev.securemessenger.shared.UsernameNormalizer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

class AuthException(val code: String) : RuntimeException(code)

class AuthService(
    private val repository: AuthRepository,
    private val passwordHasher: PasswordHasher,
    private val tokens: TokenService,
    private val mail: MailSender,
    private val clock: Clock,
    private val accessTtl: Duration = Duration.ofMinutes(15),
    private val refreshTtl: Duration = Duration.ofDays(30),
    private val challengeTtl: Duration = Duration.ofMinutes(10),
) {
    private val dummyPasswordHash by lazy { passwordHasher.hash("dummy-password-for-timing") }

    fun createInvite(): String {
        val invite = tokens.newToken()
        repository.createInvite(invite.hash)
        return invite.raw
    }

    fun register(request: RegisterRequest): RegisterResponse {
        val email = EmailNormalizer.normalize(request.email)
        val username = UsernameNormalizer.normalize(request.username)
        validatePassword(request.password)
        if (request.inviteCode.length !in 40..128) throw AuthException("INVALID_INVITE")

        val account = AccountRecord(
            id = UUID.randomUUID(),
            email = email,
            username = username,
            passwordHash = passwordHasher.hash(request.password),
            emailVerified = false,
        )
        val accepted = try {
            repository.registerPendingAccount(tokens.hashToken(request.inviteCode), account)
        } catch (_: DuplicateAccountException) {
            throw AuthException("ACCOUNT_EXISTS")
        }
        if (!accepted) throw AuthException("INVALID_INVITE")
        sendChallenge(account, MailPurpose.EMAIL_VERIFICATION)
        return RegisterResponse(account.id.toString(), account.email)
    }

    fun verifyEmail(request: EmailCodeRequest) {
        val email = normalizedEmailOrInvalidCode(request.email)
        validateCode(request.code)
        if (!repository.verifyEmailChallenge(email, tokens.hashEmailCode(request.code), now())) {
            throw AuthException("INVALID_CODE")
        }
    }

    fun resendVerification(request: ResendVerificationRequest) {
        val email = runCatching { EmailNormalizer.normalize(request.email) }.getOrNull() ?: return
        val account = repository.findAccountByEmail(email) ?: return
        if (!account.emailVerified) {
            sendChallenge(account, MailPurpose.EMAIL_VERIFICATION)
        }
    }

    fun login(request: LoginRequest): TokenResponse {
        val email = try {
            EmailNormalizer.normalize(request.email)
        } catch (_: ValidationException) {
            throw AuthException("INVALID_CREDENTIALS")
        }
        val deviceId = runCatching { UUID.fromString(request.deviceId) }.getOrNull()
            ?: throw AuthException("INVALID_DEVICE")
        val account = repository.findAccountByEmail(email)
        val passwordMatches = passwordHasher.verify(request.password, account?.passwordHash ?: dummyPasswordHash)
        if (account == null || !passwordMatches) throw AuthException("INVALID_CREDENTIALS")
        if (!account.emailVerified) throw AuthException("EMAIL_NOT_VERIFIED")
        return issueTokens(account, deviceId)
    }

    fun refresh(request: RefreshRequest): TokenResponse {
        val access = tokens.newToken()
        val refresh = tokens.newToken()
        val issuedAt = now()
        val result = repository.rotateSession(
            oldRefreshHash = tokens.hashToken(request.refreshToken),
            newAccessHash = access.hash,
            newRefreshHash = refresh.hash,
            accessExpiresAt = issuedAt + accessTtl,
            refreshExpiresAt = issuedAt + refreshTtl,
            now = issuedAt,
        )
        val session = when (result.status) {
            RefreshStatus.SUCCESS -> result.session
            RefreshStatus.INVALID, RefreshStatus.REUSED -> null
        } ?: throw AuthException("INVALID_CREDENTIALS")
        return TokenResponse(
            accessToken = access.raw,
            refreshToken = refresh.raw,
            accessExpiresAt = (issuedAt + accessTtl).toEpochMilli(),
            refreshExpiresAt = (issuedAt + refreshTtl).toEpochMilli(),
            accountId = session.accountId.toString(),
            deviceId = session.deviceId.toString(),
            username = session.username,
        )
    }

    fun recover(request: RecoveryRequest) {
        val email = runCatching { EmailNormalizer.normalize(request.email) }.getOrNull() ?: return
        val account = repository.findAccountByEmail(email) ?: return
        if (account.emailVerified) {
            sendChallenge(account, MailPurpose.PASSWORD_RECOVERY)
        }
    }

    fun resetPassword(request: PasswordResetRequest) {
        val email = normalizedEmailOrInvalidCode(request.email)
        validateCode(request.code)
        validatePassword(request.newPassword)
        val passwordHash = passwordHasher.hash(request.newPassword)
        if (!repository.resetPasswordWithChallenge(email, tokens.hashEmailCode(request.code), passwordHash, now())) {
            throw AuthException("INVALID_CODE")
        }
    }

    fun logout(request: LogoutRequest) {
        repository.revokeByRefresh(tokens.hashToken(request.refreshToken))
    }

    fun authenticateAccess(raw: String): AuthenticatedSession? {
        if (raw.isBlank() || raw.length > 128) return null
        return repository.findActiveSessionByAccess(tokens.hashToken(raw), now())
    }

    fun findByUsername(raw: String): UserResponse? {
        val username = UsernameNormalizer.normalize(raw)
        return repository.findAccountByUsername(username)?.let {
            UserResponse(it.id.toString(), it.username)
        }
    }

    private fun issueTokens(account: AccountRecord, deviceId: UUID): TokenResponse {
        val access = tokens.newToken()
        val refresh = tokens.newToken()
        val issuedAt = now()
        try {
            repository.createSession(
                accountId = account.id,
                deviceId = deviceId,
                accessHash = access.hash,
                refreshHash = refresh.hash,
                accessExpiresAt = issuedAt + accessTtl,
                refreshExpiresAt = issuedAt + refreshTtl,
                now = issuedAt,
            )
        } catch (_: DeviceLimitException) {
            throw AuthException("DEVICE_LIMIT")
        }
        return TokenResponse(
            accessToken = access.raw,
            refreshToken = refresh.raw,
            accessExpiresAt = (issuedAt + accessTtl).toEpochMilli(),
            refreshExpiresAt = (issuedAt + refreshTtl).toEpochMilli(),
            accountId = account.id.toString(),
            deviceId = deviceId.toString(),
            username = account.username,
        )
    }

    private fun sendChallenge(account: AccountRecord, purpose: MailPurpose) {
        val code = tokens.newEmailCode()
        val createdAt = now()
        repository.replaceChallenge(
            accountId = account.id,
            purpose = when (purpose) {
                MailPurpose.EMAIL_VERIFICATION -> ChallengePurpose.EMAIL_VERIFICATION
                MailPurpose.PASSWORD_RECOVERY -> ChallengePurpose.PASSWORD_RECOVERY
            },
            codeHash = code.hash,
            createdAt = createdAt,
            expiresAt = createdAt + challengeTtl,
        )
        mail.sendCode(account.email, purpose, code.raw)
    }

    private fun normalizedEmailOrInvalidCode(raw: String): String = try {
        EmailNormalizer.normalize(raw)
    } catch (_: ValidationException) {
        throw AuthException("INVALID_CODE")
    }

    private fun validateCode(code: String) {
        if (!Regex("[0-9]{8}").matches(code)) throw AuthException("INVALID_CODE")
    }

    private fun validatePassword(password: String) {
        when (PasswordPolicy.validate(password)) {
            PasswordError.TOO_SHORT -> throw AuthException("PASSWORD_TOO_SHORT")
            PasswordError.TOO_LONG -> throw AuthException("PASSWORD_TOO_LONG")
            null -> Unit
        }
    }

    private fun now(): Instant = clock.instant()
}
