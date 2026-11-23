package dev.securemessenger.server.auth

import dev.securemessenger.server.storage.AuthRepository
import java.time.Instant
import java.util.UUID

class FakeAuthRepository : AuthRepository {
    data class Challenge(
        val accountId: UUID,
        val purpose: ChallengePurpose,
        val codeHash: String,
        val expiresAt: Instant,
        var attempts: Int = 0,
        var consumed: Boolean = false,
    )

    data class Session(
        val session: AuthenticatedSession,
        var accessHash: String,
        var refreshHash: String,
        var accessExpiresAt: Instant,
        var refreshExpiresAt: Instant,
        var revoked: Boolean = false,
    )

    val invites = mutableSetOf<String>()
    val consumedInvites = mutableSetOf<String>()
    val accounts = mutableMapOf<UUID, AccountRecord>()
    val challenges = mutableListOf<Challenge>()
    val sessions = mutableListOf<Session>()
    val revokedDevices = mutableSetOf<UUID>()
    val activeDevices = mutableMapOf<UUID, UUID>()
    private val usedRefreshTokens = mutableMapOf<String, UUID>()

    override fun createInvite(codeHash: String) {
        invites += codeHash
    }

    override fun registerPendingAccount(inviteHash: String, account: AccountRecord): Boolean {
        if (inviteHash !in invites || inviteHash in consumedInvites) return false
        if (accounts.values.any { it.email == account.email || it.username == account.username }) {
            throw DuplicateAccountException()
        }
        consumedInvites += inviteHash
        accounts[account.id] = account
        return true
    }

    override fun findAccountByEmail(email: String): AccountRecord? = accounts.values.singleOrNull { it.email == email }

    override fun findAccountByUsername(username: String): AccountRecord? =
        accounts.values.singleOrNull { it.username == username && it.emailVerified }

    override fun replaceChallenge(
        accountId: UUID,
        purpose: ChallengePurpose,
        codeHash: String,
        createdAt: Instant,
        expiresAt: Instant,
    ) {
        challenges.filter { it.accountId == accountId && it.purpose == purpose }.forEach { it.consumed = true }
        challenges += Challenge(accountId, purpose, codeHash, expiresAt)
    }

    override fun verifyEmailChallenge(email: String, codeHash: String, now: Instant): Boolean {
        val account = findAccountByEmail(email) ?: return false
        val challenge = challenges.lastOrNull {
            it.accountId == account.id && it.purpose == ChallengePurpose.EMAIL_VERIFICATION && !it.consumed
        } ?: return false
        if (!validate(challenge, codeHash, now)) return false
        challenge.consumed = true
        accounts[account.id] = account.copy(emailVerified = true)
        return true
    }

    override fun resetPasswordWithChallenge(email: String, codeHash: String, newPasswordHash: String, now: Instant): Boolean {
        val account = findAccountByEmail(email) ?: return false
        val challenge = challenges.lastOrNull {
            it.accountId == account.id && it.purpose == ChallengePurpose.PASSWORD_RECOVERY && !it.consumed
        } ?: return false
        if (!validate(challenge, codeHash, now)) return false
        challenge.consumed = true
        accounts[account.id] = account.copy(passwordHash = newPasswordHash)
        sessions.filter { it.session.accountId == account.id }.forEach { it.revoked = true }
        activeDevices.remove(account.id)?.let { revokedDevices += it }
        return true
    }

    override fun createSession(
        accountId: UUID,
        deviceId: UUID,
        accessHash: String,
        refreshHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        now: Instant,
    ) {
        val active = activeDevices[accountId]
        if (active != null && active != deviceId) throw DeviceLimitException()
        activeDevices[accountId] = deviceId
        revokedDevices -= deviceId
        val account = accounts.getValue(accountId)
        sessions += Session(
            AuthenticatedSession(UUID.randomUUID(), accountId, deviceId, account.username),
            accessHash,
            refreshHash,
            accessExpiresAt,
            refreshExpiresAt,
        )
    }

    override fun findActiveSessionByAccess(accessHash: String, now: Instant): AuthenticatedSession? = sessions.singleOrNull {
        it.accessHash == accessHash && !it.revoked && it.accessExpiresAt > now && it.session.deviceId !in revokedDevices
    }?.session

    override fun rotateSession(
        oldRefreshHash: String,
        newAccessHash: String,
        newRefreshHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        now: Instant,
    ): RefreshResult {
        val current = sessions.singleOrNull { it.refreshHash == oldRefreshHash }
        if (current == null) {
            val sessionId = usedRefreshTokens[oldRefreshHash] ?: return RefreshResult(RefreshStatus.INVALID)
            sessions.single { it.session.sessionId == sessionId }.revoked = true
            return RefreshResult(RefreshStatus.REUSED)
        }
        if (current.revoked || current.refreshExpiresAt <= now) return RefreshResult(RefreshStatus.INVALID)
        usedRefreshTokens[oldRefreshHash] = current.session.sessionId
        current.accessHash = newAccessHash
        current.refreshHash = newRefreshHash
        current.accessExpiresAt = accessExpiresAt
        current.refreshExpiresAt = refreshExpiresAt
        return RefreshResult(RefreshStatus.SUCCESS, current.session)
    }

    override fun revokeByRefresh(refreshHash: String) {
        sessions.singleOrNull { it.refreshHash == refreshHash }?.revoked = true
        usedRefreshTokens[refreshHash]?.let { id -> sessions.single { it.session.sessionId == id }.revoked = true }
    }

    private fun validate(challenge: Challenge, codeHash: String, now: Instant): Boolean {
        if (challenge.expiresAt <= now || challenge.attempts >= 5) {
            challenge.consumed = true
            return false
        }
        if (challenge.codeHash != codeHash) {
            challenge.attempts++
            return false
        }
        return true
    }
}
