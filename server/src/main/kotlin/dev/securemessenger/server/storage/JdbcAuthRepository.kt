package dev.securemessenger.server.storage

import dev.securemessenger.server.auth.AccountRecord
import dev.securemessenger.server.auth.AuthenticatedSession
import dev.securemessenger.server.auth.ChallengePurpose
import dev.securemessenger.server.auth.DeviceLimitException
import dev.securemessenger.server.auth.DuplicateAccountException
import dev.securemessenger.server.auth.RefreshResult
import dev.securemessenger.server.auth.RefreshStatus
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcAuthRepository(private val dataSource: DataSource) : AuthRepository {
    override fun createInvite(codeHash: String) {
        dataSource.connection.use { connection ->
            connection.execute(
                "INSERT INTO invites(id, code_hash) VALUES (?, ?)",
                UUID.randomUUID(),
                codeHash,
            )
        }
    }

    override fun registerPendingAccount(inviteHash: String, account: AccountRecord): Boolean = dataSource.transaction { connection ->
        val inviteId = connection.queryOne(
            "SELECT id FROM invites WHERE code_hash = ? AND consumed_at IS NULL FOR UPDATE",
            { rs -> rs.getObject("id", UUID::class.java) },
            inviteHash,
        ) ?: return@transaction false

        try {
            connection.execute(
                """
                INSERT INTO accounts(id, email, username, username_normalized, password_hash, email_verified)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                account.id,
                account.email,
                account.username,
                account.username,
                account.passwordHash,
                account.emailVerified,
            )
        } catch (error: SQLException) {
            if (error.sqlState == "23505") {
                throw DuplicateAccountException()
            }
            throw error
        }

        connection.execute(
            "UPDATE invites SET consumed_at = now(), consumed_by = ? WHERE id = ?",
            account.id,
            inviteId,
        )
        true
    }

    override fun findAccountByEmail(email: String): AccountRecord? = dataSource.connection.use { connection ->
        connection.queryOne(
            "$accountSelect WHERE email = ?",
            ::account,
            email,
        )
    }

    override fun findAccountByUsername(username: String): AccountRecord? = dataSource.connection.use { connection ->
        connection.queryOne(
            "$accountSelect WHERE username_normalized = ? AND email_verified = TRUE",
            ::account,
            username,
        )
    }

    override fun replaceChallenge(
        accountId: UUID,
        purpose: ChallengePurpose,
        codeHash: String,
        createdAt: Instant,
        expiresAt: Instant,
    ) {
        dataSource.transaction { connection ->
            connection.execute(
                "UPDATE email_challenges SET consumed_at = ? WHERE account_id = ? AND purpose = ? AND consumed_at IS NULL",
                createdAt,
                accountId,
                purpose.name,
            )
            connection.execute(
                """
                INSERT INTO email_challenges(id, account_id, purpose, code_hmac, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                accountId,
                purpose.name,
                codeHash,
                createdAt,
                expiresAt,
            )
        }
    }

    override fun verifyEmailChallenge(email: String, codeHash: String, now: Instant): Boolean = dataSource.transaction { connection ->
        val challenge = connection.lockLatestChallenge(email, ChallengePurpose.EMAIL_VERIFICATION)
            ?: return@transaction false
        if (!connection.validateChallenge(challenge, codeHash, now)) {
            return@transaction false
        }
        connection.execute("UPDATE email_challenges SET consumed_at = ? WHERE id = ?", now, challenge.id)
        connection.execute("UPDATE accounts SET email_verified = TRUE WHERE id = ?", challenge.accountId)
        true
    }

    override fun resetPasswordWithChallenge(
        email: String,
        codeHash: String,
        newPasswordHash: String,
        now: Instant,
    ): Boolean = dataSource.transaction { connection ->
        val challenge = connection.lockLatestChallenge(email, ChallengePurpose.PASSWORD_RECOVERY)
            ?: return@transaction false
        if (!connection.validateChallenge(challenge, codeHash, now)) {
            return@transaction false
        }
        connection.execute("UPDATE email_challenges SET consumed_at = ? WHERE id = ?", now, challenge.id)
        connection.execute("UPDATE accounts SET password_hash = ? WHERE id = ?", newPasswordHash, challenge.accountId)
        connection.execute("UPDATE sessions SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL", now, challenge.accountId)
        connection.execute("UPDATE devices SET revoked_at = ? WHERE account_id = ? AND revoked_at IS NULL", now, challenge.accountId)
        true
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
        dataSource.transaction { connection ->
            connection.queryOne(
                "SELECT id FROM accounts WHERE id = ? FOR UPDATE",
                { rs -> rs.getObject("id", UUID::class.java) },
                accountId,
            ) ?: throw IllegalStateException("Account does not exist")
            val owner = connection.queryOne(
                "SELECT account_id FROM devices WHERE id = ? FOR UPDATE",
                { rs -> rs.getObject("account_id", UUID::class.java) },
                deviceId,
            )
            if (owner != null && owner != accountId) throw DeviceLimitException()
            val devices = connection.queryAll(
                "SELECT id, revoked_at FROM devices WHERE account_id = ? FOR UPDATE",
                { rs -> rs.getObject("id", UUID::class.java) to rs.getTimestamp("revoked_at") },
                accountId,
            )
            if (devices.any { (id, revokedAt) -> id != deviceId && revokedAt == null }) {
                throw DeviceLimitException()
            }
            if (owner == null) {
                val inserted = connection.execute(
                    "INSERT INTO devices(id, account_id, created_at) VALUES (?, ?, ?) ON CONFLICT (id) DO NOTHING",
                    deviceId,
                    accountId,
                    now,
                )
                if (inserted != 1) throw DeviceLimitException()
            } else {
                connection.execute("UPDATE devices SET revoked_at = NULL WHERE id = ? AND account_id = ?", deviceId, accountId)
            }
            connection.execute(
                """
                INSERT INTO sessions(
                    id, account_id, device_id, access_token_hash, refresh_token_hash,
                    access_expires_at, refresh_expires_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                accountId,
                deviceId,
                accessHash,
                refreshHash,
                accessExpiresAt,
                refreshExpiresAt,
                now,
            )
        }
    }

    override fun findActiveSessionByAccess(accessHash: String, now: Instant): AuthenticatedSession? = dataSource.connection.use { connection ->
        connection.queryOne(
            """
            $sessionSelect
            WHERE s.access_token_hash = ? AND s.revoked_at IS NULL
              AND s.access_expires_at > ? AND d.revoked_at IS NULL AND a.email_verified = TRUE
            """,
            ::session,
            accessHash,
            now,
        )
    }

    override fun rotateSession(
        oldRefreshHash: String,
        newAccessHash: String,
        newRefreshHash: String,
        accessExpiresAt: Instant,
        refreshExpiresAt: Instant,
        now: Instant,
    ): RefreshResult = dataSource.transaction { connection ->
        val current = connection.queryOne(
            """
            $sessionSelect
            WHERE s.refresh_token_hash = ?
            FOR UPDATE OF s
            """,
            { rs ->
                RefreshSessionRecord(
                    session = session(rs),
                    refreshExpiresAt = rs.getTimestamp("refresh_expires_at").toInstant(),
                    revoked = rs.getTimestamp("revoked_at") != null,
                )
            },
            oldRefreshHash,
        )

        if (current == null) {
            val usedSessionId = connection.queryOne(
                "SELECT session_id FROM used_refresh_tokens WHERE token_hash = ?",
                { rs -> rs.getObject("session_id", UUID::class.java) },
                oldRefreshHash,
            )
            if (usedSessionId != null) {
                connection.execute("UPDATE sessions SET revoked_at = ? WHERE id = ? AND revoked_at IS NULL", now, usedSessionId)
                return@transaction RefreshResult(RefreshStatus.REUSED)
            }
            return@transaction RefreshResult(RefreshStatus.INVALID)
        }

        val (session, refreshExpiresAtFromDb, revoked) = current
        if (revoked || refreshExpiresAtFromDb <= now) {
            return@transaction RefreshResult(RefreshStatus.INVALID)
        }

        connection.execute(
            "INSERT INTO used_refresh_tokens(token_hash, session_id, used_at) VALUES (?, ?, ?)",
            oldRefreshHash,
            session.sessionId,
            now,
        )
        connection.execute(
            """
            UPDATE sessions
            SET access_token_hash = ?, refresh_token_hash = ?, access_expires_at = ?, refresh_expires_at = ?
            WHERE id = ?
            """,
            newAccessHash,
            newRefreshHash,
            accessExpiresAt,
            refreshExpiresAt,
            session.sessionId,
        )
        RefreshResult(RefreshStatus.SUCCESS, session)
    }

    override fun revokeByRefresh(refreshHash: String) {
        dataSource.transaction { connection ->
            connection.execute("UPDATE sessions SET revoked_at = now() WHERE refresh_token_hash = ? AND revoked_at IS NULL", refreshHash)
            connection.execute(
                """
                UPDATE sessions SET revoked_at = now()
                WHERE id IN (SELECT session_id FROM used_refresh_tokens WHERE token_hash = ?)
                  AND revoked_at IS NULL
                """,
                refreshHash,
            )
        }
    }

    private fun Connection.lockLatestChallenge(email: String, purpose: ChallengePurpose): ChallengeRecord? = queryOne(
        """
        SELECT c.id, c.account_id, c.code_hmac, c.expires_at, c.attempts
        FROM email_challenges c
        JOIN accounts a ON a.id = c.account_id
        WHERE a.email = ? AND c.purpose = ? AND c.consumed_at IS NULL
        ORDER BY c.created_at DESC
        LIMIT 1
        FOR UPDATE OF c
        """,
        { rs ->
            ChallengeRecord(
                id = rs.getObject("id", UUID::class.java),
                accountId = rs.getObject("account_id", UUID::class.java),
                codeHash = rs.getString("code_hmac"),
                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                attempts = rs.getInt("attempts"),
            )
        },
        email,
        purpose.name,
    )

    private fun Connection.validateChallenge(challenge: ChallengeRecord, codeHash: String, now: Instant): Boolean {
        if (challenge.expiresAt <= now || challenge.attempts >= 5) {
            execute("UPDATE email_challenges SET consumed_at = ? WHERE id = ?", now, challenge.id)
            return false
        }
        val matches = MessageDigest.isEqual(
            challenge.codeHash.toByteArray(Charsets.UTF_8),
            codeHash.toByteArray(Charsets.UTF_8),
        )
        if (!matches) {
            execute("UPDATE email_challenges SET attempts = attempts + 1 WHERE id = ?", challenge.id)
            return false
        }
        return true
    }

    private data class RefreshSessionRecord(
        val session: AuthenticatedSession,
        val refreshExpiresAt: Instant,
        val revoked: Boolean,
    )

    private data class ChallengeRecord(
        val id: UUID,
        val accountId: UUID,
        val codeHash: String,
        val expiresAt: Instant,
        val attempts: Int,
    )

    private companion object {
        val accountSelect = """
            SELECT id, email, username, password_hash, email_verified FROM accounts
        """
        val sessionSelect = """
            SELECT s.id AS session_id, s.account_id, s.device_id, a.username, s.refresh_expires_at, s.revoked_at
            FROM sessions s
            JOIN accounts a ON a.id = s.account_id
            JOIN devices d ON d.id = s.device_id
        """

        fun account(rs: ResultSet): AccountRecord = AccountRecord(
            id = rs.getObject("id", UUID::class.java),
            email = rs.getString("email"),
            username = rs.getString("username"),
            passwordHash = rs.getString("password_hash"),
            emailVerified = rs.getBoolean("email_verified"),
        )

        fun session(rs: ResultSet): AuthenticatedSession = AuthenticatedSession(
            sessionId = rs.getObject("session_id", UUID::class.java),
            accountId = rs.getObject("account_id", UUID::class.java),
            deviceId = rs.getObject("device_id", UUID::class.java),
            username = rs.getString("username"),
        )
    }
}

private fun PreparedStatement.bind(parameters: Array<out Any?>) {
    parameters.forEachIndexed { index, value ->
        when (value) {
            is Instant -> setTimestamp(index + 1, Timestamp.from(value))
            else -> setObject(index + 1, value)
        }
    }
}

private fun <T> Connection.queryOne(sql: String, mapper: (ResultSet) -> T, vararg parameters: Any?): T? =
    prepareStatement(sql).use { statement ->
        statement.bind(parameters)
        statement.executeQuery().use { rs ->
            if (rs.next()) mapper(rs) else null
        }
    }

private fun <T> Connection.queryAll(sql: String, mapper: (ResultSet) -> T, vararg parameters: Any?): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(parameters)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(mapper(rs))
            }
        }
    }

private fun Connection.execute(sql: String, vararg parameters: Any?): Int = prepareStatement(sql).use { statement ->
    statement.bind(parameters)
    statement.executeUpdate()
}
