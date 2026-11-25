package dev.securemessenger.server.e2ee

import dev.securemessenger.server.groups.JdbcGroupRepository
import dev.securemessenger.server.storage.DatabaseFactory
import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.KyberPreKeyDto
import dev.securemessenger.shared.PreKeyDto
import dev.securemessenger.shared.SendGroupMessageRequest
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.SignedPreKeyDto
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.measureTime
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HundredUserLoadTest {
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        private lateinit var dataSource: DataSource

        @BeforeAll
        @JvmStatic
        fun start() {
            postgres = EmbeddedPostgres.start()
            dataSource = postgres.postgresDatabase
            DatabaseFactory(dataSource).migrate()
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            postgres.close()
        }
    }

    @Test
    fun `hundred users and group fanout remain consistent`() {
        val messages = JdbcE2eeRepository(dataSource)
        val groups = JdbcGroupRepository(dataSource)
        val users = List(100) { index -> account(index) }
        val owner = users.first()
        val elapsed = measureTime {
            val group = groups.create(owner.accountId, users.drop(1).take(49).map { it.username })
            assertEquals(50, group.members.size)
            val groupId = UUID.fromString(group.groupId)
            val groupMessageId = UUID.randomUUID()
            group.members.filter { it.accountId != owner.accountId.toString() }.forEach { member ->
                val recipient = users.first { it.accountId.toString() == member.accountId }
                messages.enqueueGroup(
                    owner.accountId,
                    owner.deviceId,
                    groupId,
                    groupMessageId,
                    SendGroupMessageRequest(
                        groupMessageId.toString(),
                        SendMessageRequest(UUID.randomUUID().toString(), recipient.deviceId.toString(), 3, encode(256, 3)),
                    ).envelope,
                )
            }
            users.drop(1).forEach { recipient ->
                messages.enqueue(
                    owner.accountId,
                    owner.deviceId,
                    SendMessageRequest(UUID.randomUUID().toString(), recipient.deviceId.toString(), 3, encode(256, 4)),
                )
            }
        }

        val queued = users.drop(1).associate { recipient ->
            recipient.username to messages.fetchUndelivered(recipient.deviceId).size
        }
        assertEquals(100, users.size)
        users.drop(1).take(49).forEach { recipient -> assertEquals(2, queued.getValue(recipient.username)) }
        users.drop(50).forEach { recipient -> assertEquals(1, queued.getValue(recipient.username)) }
        assertEquals(148, queued.values.sum())
        assertTrue(elapsed.inWholeMilliseconds < 60_000, "Load test took $elapsed")
        println("100 users, 50 group members, 148 ciphertext envelopes: $elapsed")
    }

    private fun account(index: Int): Fixture {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        val username = "load_user_$index"
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO accounts(id, email, username, username_normalized, password_hash, email_verified) VALUES (?, ?, ?, ?, 'hash', TRUE)",
            ).use {
                it.setObject(1, accountId)
                it.setString(2, "$username@example.com")
                it.setString(3, username)
                it.setString(4, username)
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO devices(id, account_id) VALUES (?, ?)").use {
                it.setObject(1, deviceId)
                it.setObject(2, accountId)
                it.executeUpdate()
            }
        }
        val ec = encode(33, MessageDigest.getInstance("SHA-256").digest(accountId.toString().toByteArray()))
        val kem = encode(1569, 2)
        val signature = encode(64, 4)
        JdbcE2eeRepository(dataSource).storeDeviceKeys(
            accountId,
            deviceId,
            DeviceKeysUpload(
                registrationId = index + 1,
                identityKey = ec,
                signedPreKey = SignedPreKeyDto(1, ec, signature),
                preKeys = listOf(PreKeyDto(1, ec)),
                kyberPreKeys = listOf(KyberPreKeyDto(2, kem, signature)),
                lastResortKyberPreKey = KyberPreKeyDto(3, kem, signature),
            ),
        )
        return Fixture(accountId, deviceId, username)
    }

    private fun encode(size: Int, value: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(size) { value.toByte() })

    private fun encode(size: Int, value: ByteArray): String = Base64.getEncoder().encodeToString(value.copyOf(size))

    private data class Fixture(val accountId: UUID, val deviceId: UUID, val username: String)
}
