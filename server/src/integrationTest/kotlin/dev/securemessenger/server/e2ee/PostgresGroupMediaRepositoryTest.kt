package dev.securemessenger.server.e2ee

import dev.securemessenger.server.groups.JdbcGroupRepository
import dev.securemessenger.server.media.JdbcMediaRepository
import dev.securemessenger.server.media.LocalMediaStore
import dev.securemessenger.server.media.MediaAccessException
import dev.securemessenger.server.media.MediaConflictException
import dev.securemessenger.server.media.MediaNotFoundException
import dev.securemessenger.server.storage.DatabaseFactory
import dev.securemessenger.shared.MEDIA_UPLOAD_CHUNK_SIZE
import dev.securemessenger.shared.MediaUploadRequest
import dev.securemessenger.shared.SendGroupMessageRequest
import dev.securemessenger.shared.SendMessageRequest
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PostgresGroupMediaRepositoryTest {
    companion object {
        private lateinit var postgres: EmbeddedPostgres
        private lateinit var dataSource: DataSource
        private lateinit var groups: JdbcGroupRepository
        private lateinit var e2ee: JdbcE2eeRepository
        private lateinit var mediaRoot: Path
        private lateinit var mediaStore: LocalMediaStore
        private lateinit var media: JdbcMediaRepository
        private val sequence = AtomicInteger()

        @BeforeAll
        @JvmStatic
        fun start() {
            postgres = EmbeddedPostgres.start()
            dataSource = postgres.postgresDatabase
            DatabaseFactory(dataSource).migrate()
            groups = JdbcGroupRepository(dataSource)
            e2ee = JdbcE2eeRepository(dataSource)
            mediaRoot = Files.createTempDirectory("messenger-media-test")
            mediaStore = LocalMediaStore(mediaRoot)
            media = JdbcMediaRepository(dataSource, mediaStore, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            mediaRoot.toFile().deleteRecursively()
            postgres.close()
        }
    }

    @Test
    fun `group fanout is idempotent and removal purges undelivered envelopes`() {
        val fixture = fixture("fanout")
        val group = groups.create(fixture.owner, listOf(fixture.memberUsername))
        val groupMessageId = UUID.randomUUID()
        val request = SendGroupMessageRequest(
            groupMessageId.toString(),
            SendMessageRequest(UUID.randomUUID().toString(), fixture.memberDevice.toString(), 3, encode(256, 3)),
        )

        e2ee.enqueueGroup(fixture.owner, fixture.ownerDevice, UUID.fromString(group.groupId), groupMessageId, request.envelope)
        e2ee.enqueueGroup(fixture.owner, fixture.ownerDevice, UUID.fromString(group.groupId), groupMessageId, request.envelope)
        val queued = e2ee.fetchUndelivered(fixture.memberDevice)
        assertEquals(1, queued.size)
        assertEquals(group.groupId, queued.single().groupId)
        assertEquals(groupMessageId.toString(), queued.single().groupMessageId)
        assertFailsWith<MessageConflictException> {
            e2ee.enqueueGroup(
                fixture.owner,
                fixture.ownerDevice,
                UUID.fromString(group.groupId),
                groupMessageId,
                request.envelope.copy(ciphertext = encode(256, 8)),
            )
        }
        assertFailsWith<MessageAuthorizationException> {
            e2ee.enqueueGroup(fixture.outsider, fixture.outsiderDevice, UUID.fromString(group.groupId), UUID.randomUUID(), request.envelope)
        }

        groups.removeMember(fixture.owner, UUID.fromString(group.groupId), fixture.member)
        assertTrue(groups.list(fixture.member).isEmpty())
        assertTrue(e2ee.fetchUndelivered(fixture.memberDevice).isEmpty())
        assertFailsWith<RecipientNotFoundException> {
            e2ee.enqueueGroup(fixture.owner, fixture.ownerDevice, UUID.fromString(group.groupId), groupMessageId, request.envelope)
        }
    }

    @Test
    fun `new member receives only subsequent messages`() {
        val fixture = fixture("history")
        val group = groups.create(fixture.owner, listOf(fixture.memberUsername))
        val groupId = UUID.fromString(group.groupId)
        e2ee.enqueueGroup(
            fixture.owner,
            fixture.ownerDevice,
            groupId,
            UUID.randomUUID(),
            SendMessageRequest(UUID.randomUUID().toString(), fixture.memberDevice.toString(), 3, encode(128, 1)),
        )
        groups.addMembers(fixture.owner, groupId, listOf(fixture.outsiderUsername))
        assertTrue(e2ee.fetchUndelivered(fixture.outsiderDevice).isEmpty())

        val newMessageId = UUID.randomUUID()
        e2ee.enqueueGroup(
            fixture.owner,
            fixture.ownerDevice,
            groupId,
            newMessageId,
            SendMessageRequest(UUID.randomUUID().toString(), fixture.outsiderDevice.toString(), 3, encode(128, 2)),
        )
        assertEquals(newMessageId.toString(), e2ee.fetchUndelivered(fixture.outsiderDevice).single().groupMessageId)
    }

    @Test
    fun `media resume is idempotent and download follows message ACL`() {
        val fixture = fixture("media")
        val content = ByteArray(MEDIA_UPLOAD_CHUNK_SIZE + 123) { (it % 251).toByte() }
        val created = media.create(fixture.owner, fixture.ownerDevice, uploadRequest(content))
        val mediaId = UUID.fromString(created.mediaId)
        val first = content.copyOfRange(0, MEDIA_UPLOAD_CHUNK_SIZE)

        val uploading = media.upload(fixture.owner, fixture.ownerDevice, mediaId, 0, first)
        assertEquals(first.size.toLong(), uploading.uploadedBytes)
        assertEquals(uploading, media.upload(fixture.owner, fixture.ownerDevice, mediaId, 0, first))
        assertFailsWith<MediaConflictException> {
            media.upload(fixture.owner, fixture.ownerDevice, mediaId, 0, first.copyOf(100).also { it[0] = 99 })
        }
        assertFailsWith<MediaConflictException> {
            media.upload(fixture.owner, fixture.ownerDevice, mediaId, 10, ByteArray(1))
        }
        val ready = media.upload(fixture.owner, fixture.ownerDevice, mediaId, first.size.toLong(), content.copyOfRange(first.size, content.size))
        assertTrue(ready.ready)
        assertContentEquals(content.copyOfRange(1000, 1100), media.read(fixture.owner, fixture.ownerDevice, mediaId, 1000, 100).bytes)
        assertFailsWith<MediaAccessException> { media.read(fixture.member, fixture.memberDevice, mediaId, 0, 1) }

        e2ee.enqueue(
            fixture.owner,
            fixture.ownerDevice,
            SendMessageRequest(UUID.randomUUID().toString(), fixture.memberDevice.toString(), 3, encode(256, 3), created.mediaId),
        )
        assertContentEquals(content.take(32).toByteArray(), media.read(fixture.member, fixture.memberDevice, mediaId, 0, 32).bytes)
        assertFailsWith<MediaAccessException> { media.read(fixture.outsider, fixture.outsiderDevice, mediaId, 0, 1) }

        media.delete(fixture.owner, fixture.ownerDevice, mediaId)
        assertFailsWith<MediaNotFoundException> { media.read(fixture.member, fixture.memberDevice, mediaId, 0, 1) }
        assertFalse(Files.exists(mediaRoot.resolve("${created.mediaId}.ciphertext")))
    }

    @Test
    fun `wrong media hash rolls back file and metadata`() {
        val fixture = fixture("hash")
        val content = ByteArray(2048) { 7 }
        val wrongHash = ByteArray(32).joinToString("") { "%02x".format(it) }
        val created = media.create(fixture.owner, fixture.ownerDevice, MediaUploadRequest(content.size.toLong(), wrongHash))
        val mediaId = UUID.fromString(created.mediaId)

        assertFailsWith<MediaConflictException> {
            media.upload(fixture.owner, fixture.ownerDevice, mediaId, 0, content)
        }
        assertEquals(0, Files.size(mediaRoot.resolve("${created.mediaId}.ciphertext")))
        assertEquals(0, media.status(fixture.owner, fixture.ownerDevice, mediaId).uploadedBytes)
    }

    @Test
    fun `expired media is deleted`() {
        val fixture = fixture("expiry")
        val content = ByteArray(128) { 4 }
        val created = media.create(fixture.owner, fixture.ownerDevice, uploadRequest(content))
        val mediaId = UUID.fromString(created.mediaId)
        media.upload(fixture.owner, fixture.ownerDevice, mediaId, 0, content)
        val later = JdbcMediaRepository(
            dataSource,
            mediaStore,
            Clock.fixed(Instant.parse("2026-01-10T00:00:00Z"), ZoneOffset.UTC),
        )

        assertEquals(1, later.deleteExpired())
        assertFalse(Files.exists(mediaRoot.resolve("${created.mediaId}.ciphertext")))
        assertFailsWith<MediaNotFoundException> { later.status(fixture.owner, fixture.ownerDevice, mediaId) }
    }

    private fun uploadRequest(content: ByteArray) = MediaUploadRequest(
        expectedBytes = content.size.toLong(),
        ciphertextSha256 = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) },
    )

    private fun fixture(prefix: String): Fixture {
        val suffix = sequence.incrementAndGet()
        val owner = account("${prefix}_owner_$suffix")
        val member = account("${prefix}_member_$suffix")
        val outsider = account("${prefix}_other_$suffix")
        return Fixture(
            owner = owner.first,
            ownerDevice = owner.second,
            member = member.first,
            memberDevice = member.second,
            outsider = outsider.first,
            outsiderDevice = outsider.second,
            memberUsername = "${prefix}_member_$suffix",
            outsiderUsername = "${prefix}_other_$suffix",
        )
    }

    private fun account(username: String): Pair<UUID, UUID> {
        val accountId = UUID.randomUUID()
        val deviceId = UUID.randomUUID()
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO accounts(id, email, username, username_normalized, password_hash, email_verified) VALUES (?, ?, ?, ?, 'hash', TRUE)").use {
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
        return accountId to deviceId
    }

    private data class Fixture(
        val owner: UUID,
        val ownerDevice: UUID,
        val member: UUID,
        val memberDevice: UUID,
        val outsider: UUID,
        val outsiderDevice: UUID,
        val memberUsername: String,
        val outsiderUsername: String,
    )

    private fun encode(size: Int, value: Int): String = Base64.getEncoder().encodeToString(ByteArray(size) { value.toByte() })
}
