package dev.securemessenger.server.auth

import java.time.Instant
import java.util.UUID

enum class ChallengePurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RECOVERY,
}

data class AccountRecord(
    val id: UUID,
    val email: String,
    val username: String,
    val passwordHash: String,
    val emailVerified: Boolean,
)

data class AuthenticatedSession(
    val sessionId: UUID,
    val accountId: UUID,
    val deviceId: UUID,
    val username: String,
)

enum class RefreshStatus {
    SUCCESS,
    INVALID,
    REUSED,
}

data class RefreshResult(
    val status: RefreshStatus,
    val session: AuthenticatedSession? = null,
)

class DuplicateAccountException : RuntimeException("Account already exists")
class DeviceLimitException : RuntimeException("Another active device is already registered")
