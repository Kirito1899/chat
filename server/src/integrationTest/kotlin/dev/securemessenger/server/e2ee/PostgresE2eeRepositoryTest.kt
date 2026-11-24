package dev.securemessenger.server.e2ee

import dev.securemessenger.server.storage.DatabaseFactory
import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.KyberPreKeyDto
import dev.securemessenger.shared.PreKeyBundleResponse
import dev.securemessenger.shared.PreKeyDto
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.SignedPreKeyDto
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
        assertFailsWith<MessageConflictException> {
            repository.enqueue(account, device, request.copy(ciphertext = encode(256, 4)))
        }
        val messages = repository.fetchUndelivered(recipientDevice)
        assertEquals(1, messages.size)
        assertFailsWith<MessageAuthorizationException> {
            repository.markDelivered(UUID.fromString(request.messageId), device)
        }
        assertTrue(repository.markDelivered(UUID.fromString(request.messageId), recipientDevice))
        assertTrue(repository.markDelivered(UUID.fromString(request.messageId), recipientDevice))
        assertTrue(repository.fetchUndelivered(recipientDevice).isEmpty())
        assertEquals(listOf(request.messageId), repository.deliveredForSender(device))
    }

    @Test
    fun `parallel bundle fetches consume each one-time key once`() {
        val (accountId, deviceId) = createAccountWithDevice("parallel_bundles")
        val upload = keys(1, 12).copy(identityKey = encode(33, 23))
        repository.storeDeviceKeys(accountId, deviceId, upload)
        val workers = upload.preKeys.size
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        try {
            val futures = List(workers) {
                executor.submit<PreKeyBundleResponse> {
                    ready.countDown()
                    start.await()
                    repository.fetchBundleByUsername("parallel_bundles")!!
                }
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS))
            start.countDown()
            val bundles = futures.map { it.get(30, TimeUnit.SECONDS) }

            assertEquals(workers, bundles.map { it.preKey!!.id }.toSet().size)
            assertEquals(workers, bundles.map { it.kyberPreKey.id }.toSet().size)
            assertTrue(bundles.none { it.kyberPreKey.id == upload.lastResortKyberPreKey.id })
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `concurrent initial key uploads are idempotent`() {
        val (accountId, deviceId) = createAccountWithDevice("concurrent_keys")
        val upload = keys(1, 2).copy(identityKey = encode(33, 17))
        val workers = 8
        val executor = Executors.newFixedThreadPool(workers)
        val ready = CountDownLatch(workers)
        val start = CountDownLatch(1)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE FUNCTION pause_device_key_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN PERFORM pg_sleep(0.25); RETURN NEW; END $$")
                statement.execute("CREATE TRIGGER pause_device_key_insert BEFORE INSERT ON device_keys FOR EACH ROW EXECUTE FUNCTION pause_device_key_insert()")
            }
        }
        try {
            val futures = List(workers) {
                executor.submit<Unit> {
                    ready.countDown()
                    start.await()
                    runCatching { repository.storeDeviceKeys(accountId, deviceId, upload) }
                        .exceptionOrNull()
                        ?.let(failures::add)
                }
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                start.countDown()
                futures.forEach { it.get(30, TimeUnit.SECONDS) }
            } finally {
                start.countDown()
                executor.shutdownNow()
            }
        } finally {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TRIGGER pause_device_key_insert ON device_keys")
                    statement.execute("DROP FUNCTION pause_device_key_insert()")
                }
            }
        }

        assertEquals(emptyList(), failures)
    }

    @Test
    fun `message enqueue rejects a sender device owned by another account`() {
        val (attackerAccount, _) = createAccountWithDevice("attacker_user")
        val (_, recipientDevice) = createAccountWithDevice("queue_recipient")
        val request = SendMessageRequest(UUID.randomUUID().toString(), recipientDevice.toString(), 3, encode(256, 3))

        assertFailsWith<MessageAuthorizationException> {
            repository.enqueue(attackerAccount, device, request)
        }
    }

    private fun createAccountWithDevice(username: String): Pair<UUID, UUID> {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO accounts(id, email, username, username_normalized, password_hash, email_verified) VALUES (?, ?, ?, ?, ?, TRUE)").use {
                it.setObject(1, accountId)
                it.setString(2, "$username@example.com")
                it.setString(3, username)
                it.setString(4, username)
                it.setString(5, "hash")
                it.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO devices(id, account_id) VALUES (?, ?)").use {
                it.setObject(1, deviceId)
                it.setObject(2, accountId)
                it.executeUpdate()
            }
        }
        return accountId to deviceId
    }

    private fun keys(start: Int, count: Int): DeviceKeysUpload {
        val ec = encode(33, 1)
        val kem = encode(1569, 2)
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
