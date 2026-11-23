package dev.securemessenger.server.e2ee

import dev.securemessenger.server.storage.DatabaseFactory
import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.KyberPreKeyDto
import dev.securemessenger.shared.PreKeyDto
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.SignedPreKeyDto
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.Base64
import java.util.UUID
import javax.sql.DataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresE2eeRepositoryTest {
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        private lateinit var dataSource: DataSource
        private lateinit var repository: JdbcE2eeRepository
        private val account = UUID.randomUUID()
        private val device = UUID.randomUUID()

        @BeforeAll
        @JvmStatic
        fun start() {
            postgres = EmbeddedPostgres.start()
            dataSource = postgres.postgresDatabase
            DatabaseFactory(dataSource).migrate()
            repository = JdbcE2eeRepository(dataSource)
            dataSource.connection.use { connection ->
                connection.prepareStatement("INSERT INTO accounts(id, email, username, username_normalized, password_hash, email_verified) VALUES (?, ?, ?, ?, ?, TRUE)").use {
                    it.setObject(1, account)
                    it.setString(2, "e2ee@example.com")
                    it.setString(3, "e2ee_user")
                    it.setString(4, "e2ee_user")
                    it.setString(5, "hash")
                    it.executeUpdate()
                }
                connection.prepareStatement("INSERT INTO devices(id, account_id) VALUES (?, ?)").use {
                    it.setObject(1, device)
                    it.setObject(2, account)
                    it.executeUpdate()
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun stop() = postgres.close()
    }

    @Test
    fun `identity is immutable and one-time prekeys are consumed once`() {
        val upload = keys(1, 2)
        repository.storeDeviceKeys(account, device, upload)
        assertFailsWith<IdentityChangeException> {
            repository.storeDeviceKeys(account, device, upload.copy(identityKey = encode(33, 9)))
        }

        val first = repository.fetchBundleByUsername("e2ee_user")!!
        val second = repository.fetchBundleByUsername("e2ee_user")!!
        assertNotEquals(first.preKey?.id, second.preKey?.id)
        assertNotEquals(first.kyberPreKey.id, second.kyberPreKey.id)
        val exhausted = repository.fetchBundleByUsername("e2ee_user")!!
        assertNull(exhausted.preKey)
        assertEquals(upload.lastResortKyberPreKey.id, exhausted.kyberPreKey.id)
    }

    @Test
    fun `message queue is idempotent and delivery ack is recipient scoped`() {
        val recipientAccount = UUID.randomUUID()
        val recipientDevice = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO accounts(id, email, username, username_normalized, password_hash, email_verified) VALUES (?, ?, ?, ?, ?, TRUE)").use {
                it.setObject(1, recipientAccount)
                it.setString(2, "recipient@example.com")
                it.setString(3, "recipient_user")
                it.setString(4, "recipient_user")
                it.setString(5, "hash")
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO devices(id, account_id) VALUES (?, ?)").use {
                it.setObject(1, recipientDevice)
                it.setObject(2, recipientAccount)
                it.executeUpdate()
            }
        }
        val request = SendMessageRequest(UUID.randomUUID().toString(), recipientDevice.toString(), 3, encode(256, 3))
        repository.enqueue(account, device, request)
        repository.enqueue(account, device, request)
        val messages = repository.fetchUndelivered(recipientDevice)
        assertEquals(1, messages.size)
        assertFailsWith<MessageAuthorizationException> {
            repository.markDelivered(UUID.fromString(request.messageId), device)
        }
        assertTrue(repository.markDelivered(UUID.fromString(request.messageId), recipientDevice))
        assertTrue(repository.fetchUndelivered(recipientDevice).isEmpty())
    }

    private fun keys(start: Int, count: Int): DeviceKeysUpload {
        val ec = encode(33, 1)
        val kem = encode(1568, 2)
        val signature = encode(64, 4)
        return DeviceKeysUpload(
            registrationId = 123,
            identityKey = ec,
            signedPreKey = SignedPreKeyDto(1, ec, signature),
            preKeys = List(count) { PreKeyDto(start + it, ec) },
            kyberPreKeys = List(count) { KyberPreKeyDto(start + 100 + it, kem, signature) },
            lastResortKyberPreKey = KyberPreKeyDto(999, kem, signature),
        )
    }

    private fun encode(size: Int, value: Int): String =
        Base64.getEncoder().encodeToString(ByteArray(size) { value.toByte() })
}
