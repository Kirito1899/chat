package dev.securemessenger.server.storage

import dev.securemessenger.server.auth.AccountRecord
import dev.securemessenger.server.auth.AuthenticatedSession
import dev.securemessenger.server.auth.ChallengePurpose
import dev.securemessenger.server.auth.RefreshResult
import java.time.Instant
import java.util.UUID

interface AuthRepository {
    fun createInvite(codeHash: String)
    fun registerPendingAccount(inviteHash: String, account: AccountRecord): Boolean
    fun findAccountByEmail(email: String): AccountRecord?
    fun findAccountByUsername(username: String): AccountRecord?
    fun replaceChallenge(
        accountId: UUID,
        purpose: ChallengePurpose,
        codeHash: String,
        createdAt: Instant,
        expiresAt: Instant,
    )

    fun verifyEmailChallenge(email: String, codeHash: String, now: Instant): Boolean
    fun resetPasswordWithChallenge(email: String, codeHash: String, newPasswordHash: String, now: Instant): Boolean

    fun createSession(
        accountId: UUID,
        deviceId: UUID,
        accessHash: String,
        refreshHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        now: Instant,
    )

    fun findActiveSessionByAccess(accessHash: String, now: Instant): AuthenticatedSession?
    fun rotateSession(
        oldRefreshHash: String,
        newAccessHash: String,
        newRefreshHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        now: Instant,
    ): RefreshResult

    fun revokeByRefresh(refreshHash: String)
}
