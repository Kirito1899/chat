package dev.securemessenger.server.storage

import dev.securemessenger.server.auth.AccountRecord
import dev.securemessenger.server.auth.ChallengePurpose
import dev.securemessenger.server.auth.DeviceLimitException
import dev.securemessenger.server.auth.DuplicateAccountException
import dev.securemessenger.server.auth.RefreshStatus
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresAuthRepositoryTest {
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        private lateinit var repository: JdbcAuthRepository

        @BeforeAll
        @JvmStatic
        fun start() {
            postgres = EmbeddedPostgres.start()
            val dataSource = postgres.postgresDatabase
            DatabaseFactory(dataSource).migrate()
            repository = JdbcAuthRepository(dataSource)
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            postgres.close()
        }
    }

    @Test
    fun `invite is consumed atomically with pending account creation`() {
        val invite = "invite-hash-7"
        repository.createInvite(invite)
        val account = newAccount("first")

        assertTrue(repository.registerPendingAccount(invite, account))
        assertFalse(repository.registerPendingAccount(invite, account.copy(id = UUID.randomUUID(), email = "other@example.com", username = "other_user")))
        repository.createInvite("invite-hash-9")
        assertFailsWith<DuplicateAccountException> {
            repository.registerPendingAccount("invite-hash-9", account.copy(id = UUID.randomUUID(), username = "different_name"))
        }
    }

    @Test
    fun `email challenge and refresh rotation preserve session invariants`() {
        val account = verifiedAccount("rotation")
        val now = Instant.now()
        val device = UUID.randomUUID()
        repository.createSession(
            account.id,
            device,
            "access-old",
            "refresh-old",
            now + Duration.ofMinutes(15),
            now + Duration.ofDays(30),
            now,
        )
        assertNotNull(repository.findActiveSessionByAccess("access-old", now))

        val rotated = repository.rotateSession(
            "refresh-old",
            "access-new",
            "refresh-new",
            now + Duration.ofMinutes(15),
            now + Duration.ofDays(30),
            now,
        )
        assertEquals(RefreshStatus.SUCCESS, rotated.status)
        assertNull(repository.findActiveSessionByAccess("access-old", now))
        assertNotNull(repository.findActiveSessionByAccess("access-new", now))

        assertEquals(
            RefreshStatus.REUSED,
            repository.rotateSession(
                "refresh-old",
                "access-reused",
                "refresh-reused",
                now + Duration.ofMinutes(15),
                now + Duration.ofDays(30),
                now,
            ).status,
        )
        assertNull(repository.findActiveSessionByAccess("access-new", now))
    }

    @Test
    fun `password reset revokes sessions and permits same device recovery`() {
        val account = verifiedAccount("recovery")
        val now = Instant.now()
        val device = UUID.randomUUID()
        repository.createSession(
            account.id,
            device,
            "access-reset",
            "refresh-reset",
            now + Duration.ofMinutes(15),
            now + Duration.ofDays(30),
            now,
        )
        repository.replaceChallenge(
            account.id,
            ChallengePurpose.PASSWORD_RECOVERY,
            "recovery-code-hash",
            now,
            now + Duration.ofMinutes(10),
        )
        assertFalse(repository.resetPasswordWithChallenge(account.email, "wrong-code", "unused", now))
        assertTrue(repository.resetPasswordWithChallenge(account.email, "recovery-code-hash", "new-hash", now))
        assertEquals("new-hash", repository.findAccountByEmail(account.email)?.passwordHash)
        assertNull(repository.findActiveSessionByAccess("access-reset", now))

        repository.createSession(
            account.id,
            device,
            "access-recovered",
            "refresh-recovered",
            now + Duration.ofMinutes(15),
            now + Duration.ofDays(30),
            now,
        )
        assertNotNull(repository.findActiveSessionByAccess("access-recovered", now))
    }

    @Test
    fun `same device can log in again after logout without enabling another device`() {
        val account = verifiedAccount("relogin")
        val now = Instant.now()
        val device = UUID.randomUUID()
        repository.createSession(
            account.id,
            device,
            "access-before-logout",
            "refresh-before-logout",
            now + Duration.ofMinutes(15),
            now + Duration.ofDays(30),
            now,
        )
        repository.revokeByRefresh("refresh-before-logout")

        repository.createSession(
            account.id,
            device,
            "access-after-logout",
            "refresh-after-logout",
            now + Duration.ofMinutes(15),
            now + Duration.ofDays(30),
            now,
        )

        assertNull(repository.findActiveSessionByAccess("access-before-logout", now))
        assertEquals(device, repository.findActiveSessionByAccess("access-after-logout", now)?.deviceId)
        assertFailsWith<DeviceLimitException> {
            repository.createSession(
                account.id,
                UUID.randomUUID(),
                "access-other-device",
                "refresh-other-device",
                now + Duration.ofMinutes(15),
                now + Duration.ofDays(30),
                now,
            )
        }
    }

    @Test
    fun `device identifier cannot be reassigned from a revoked foreign account`() {
        val owner = verifiedAccount("device_owner")
        val other = verifiedAccount("device_other")
        val now = Instant.now()
        val device = UUID.randomUUID()
        repository.createSession(
            owner.id,
            device,
            "access-owner",
            "refresh-owner",
            now + Duration.ofMinutes(15),
            now + Duration.ofDays(30),
            now,
        )
        repository.replaceChallenge(
            owner.id,
            ChallengePurpose.PASSWORD_RECOVERY,
            "device-recovery-code",
            now,
            now + Duration.ofMinutes(10),
        )
        assertTrue(repository.resetPasswordWithChallenge(owner.email, "device-recovery-code", "changed", now))

        assertFailsWith<DeviceLimitException> {
            repository.createSession(
                other.id,
                device,
                "access-foreign-device",
                "refresh-foreign-device",
                now + Duration.ofMinutes(15),
                now + Duration.ofDays(30),
                now,
            )
        }
        assertNull(repository.findActiveSessionByAccess("access-foreign-device", now))
    }

    private fun newAccount(suffix: String): AccountRecord = AccountRecord(
        id = UUID.randomUUID(),
        email = "$suffix@example.com",
        username = "${suffix}_user",
        passwordHash = "argon2id-hash",
        emailVerified = false,
    )

    private fun verifiedAccount(suffix: String): AccountRecord {
        val account = newAccount(suffix)
        val invite = "invite-$suffix"
        repository.createInvite(invite)
        assertTrue(repository.registerPendingAccount(invite, account))
        val now = Instant.now()
        repository.replaceChallenge(
            account.id,
            ChallengePurpose.EMAIL_VERIFICATION,
            "verify-code-hash-$suffix",
            now,
            now + Duration.ofMinutes(10),
        )
        assertTrue(repository.verifyEmailChallenge(account.email, "verify-code-hash-$suffix", now))
        return repository.findAccountByEmail(account.email)!!
    }
}
