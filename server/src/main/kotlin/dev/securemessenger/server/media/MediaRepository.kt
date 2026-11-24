package dev.securemessenger.server.media

import dev.securemessenger.server.storage.transaction
import dev.securemessenger.shared.MEDIA_TTL_MILLIS
import dev.securemessenger.shared.MEDIA_UPLOAD_CHUNK_SIZE
import dev.securemessenger.shared.MediaStatusResponse
import dev.securemessenger.shared.MediaUploadRequest
import dev.securemessenger.shared.MediaUploadResponse
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

interface MediaRepository {
    fun create(ownerAccountId: UUID, ownerDeviceId: UUID, request: MediaUploadRequest): MediaUploadResponse
    fun status(accountId: UUID, deviceId: UUID, mediaId: UUID): MediaStatusResponse
    fun upload(accountId: UUID, deviceId: UUID, mediaId: UUID, offset: Long, bytes: ByteArray): MediaStatusResponse
    fun read(accountId: UUID, deviceId: UUID, mediaId: UUID, offset: Long, length: Int): MediaChunk
    fun delete(accountId: UUID, deviceId: UUID, mediaId: UUID)
    fun deleteExpired(): Int
}

data class MediaChunk(val bytes: ByteArray, val totalSize: Long)

interface MediaStore {
    fun create(mediaId: UUID)
    fun append(mediaId: UUID, offset: Long, bytes: ByteArray)
    fun truncate(mediaId: UUID, size: Long)
    fun read(mediaId: UUID, offset: Long, length: Int): ByteArray
    fun matches(mediaId: UUID, offset: Long, expected: ByteArray): Boolean
    fun sha256(mediaId: UUID): String
    fun delete(mediaId: UUID)
}

class LocalMediaStore(private val root: Path) : MediaStore {
    override fun create(mediaId: UUID) {
        Files.createDirectories(root)
        Files.createFile(path(mediaId))
    }

    override fun append(mediaId: UUID, offset: Long, bytes: ByteArray) {
        FileChannel.open(path(mediaId), StandardOpenOption.WRITE).use { channel ->
            if (channel.size() < offset) throw MediaConflictException("Media offset exceeds stored size")
            if (channel.size() > offset) channel.truncate(offset)
            channel.position(offset)
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }

    override fun truncate(mediaId: UUID, size: Long) {
        val path = path(mediaId)
        if (Files.exists(path)) {
            FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                if (channel.size() > size) channel.truncate(size)
            }
        }
    }

    override fun read(mediaId: UUID, offset: Long, length: Int): ByteArray = stream(mediaId, offset).use { input ->
        input.readNBytes(length)
    }

    override fun matches(mediaId: UUID, offset: Long, expected: ByteArray): Boolean = runCatching {
        read(mediaId, offset, expected.size).contentEquals(expected)
    }.getOrDefault(false)

    override fun sha256(mediaId: UUID): String {
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(Files.newInputStream(path(mediaId)), digest).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (input.read(buffer) >= 0) {
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    override fun delete(mediaId: UUID) {
        Files.deleteIfExists(path(mediaId))
    }

    private fun stream(mediaId: UUID, offset: Long): InputStream {
        if (offset < 0) throw MediaConflictException("Negative media offset")
        val input = Files.newInputStream(path(mediaId))
        try {
            input.skipNBytes(offset)
            return input
        } catch (error: Throwable) {
            input.close()
            throw error
        }
    }

    private fun path(mediaId: UUID): Path = root.resolve("$mediaId.ciphertext")
}

class JdbcMediaRepository(
    private val dataSource: DataSource,
    private val store: MediaStore,
    private val clock: Clock = Clock.systemUTC(),
    private val ttl: Duration = Duration.ofMillis(MEDIA_TTL_MILLIS),
) : MediaRepository {
    override fun create(ownerAccountId: UUID, ownerDeviceId: UUID, request: MediaUploadRequest): MediaUploadResponse {
        request.validate()
        requireActiveDevice(ownerAccountId, ownerDeviceId)
        val mediaId = UUID.randomUUID()
        val now = clock.instant()
        val expiresAt = now + ttl
        try {
            dataSource.transaction { connection ->
                connection.execute(
                    """
                    INSERT INTO media_objects(id, owner_account_id, expected_size, ciphertext_sha256, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    mediaId,
                    ownerAccountId,
                    request.expectedBytes,
                    request.ciphertextSha256,
                    expiresAt,
                )
                store.create(mediaId)
            }
        } catch (error: Throwable) {
            store.delete(mediaId)
            throw error
        }
        return MediaUploadResponse(mediaId.toString(), MEDIA_UPLOAD_CHUNK_SIZE, expiresAt.toEpochMilli())
    }

    override fun status(accountId: UUID, deviceId: UUID, mediaId: UUID): MediaStatusResponse = dataSource.connection.use { connection ->
        val row = connection.mediaForRead(mediaId, accountId, deviceId)
        row.toResponse()
    }

    override fun upload(accountId: UUID, deviceId: UUID, mediaId: UUID, offset: Long, bytes: ByteArray): MediaStatusResponse {
        requireActiveDevice(accountId, deviceId)
        if (offset < 0 || bytes.isEmpty() || bytes.size > MEDIA_UPLOAD_CHUNK_SIZE) {
            throw MediaConflictException("Invalid media chunk")
        }
        return dataSource.transaction { connection ->
            val row = connection.queryOne(
                "$mediaSelect WHERE id = ? FOR UPDATE",
                ::mediaRow,
                mediaId,
            ) ?: throw MediaNotFoundException()
            if (row.ownerAccountId != accountId) throw MediaAccessException()
            if (row.deletedAt != null || row.expiresAt <= clock.instant()) throw MediaNotFoundException()
            if (row.status == "DELETED") throw MediaNotFoundException()
            if (offset < row.uploadedSize) {
                if (!store.matches(mediaId, offset, bytes)) throw MediaConflictException("Media retry differs")
                return@transaction row.toResponse()
            }
            if (offset != row.uploadedSize) throw MediaConflictException("Media offset is not sequential")
            val newSize = offset + bytes.size
            if (newSize > row.expectedSize) throw MediaConflictException("Media exceeds declared size")
            try {
                store.append(mediaId, offset, bytes)
                val ready = newSize == row.expectedSize
                if (ready && store.sha256(mediaId) != row.sha256) {
                    throw MediaConflictException("Media hash differs")
                }
                connection.execute(
                    "UPDATE media_objects SET uploaded_size = ?, status = ? WHERE id = ?",
                    newSize,
                    if (ready) "READY" else "UPLOADING",
                    mediaId,
                )
                row.copy(uploadedSize = newSize, status = if (ready) "READY" else "UPLOADING").toResponse()
            } catch (error: Throwable) {
                store.truncate(mediaId, offset)
                throw error
            }
        }
    }

    override fun read(accountId: UUID, deviceId: UUID, mediaId: UUID, offset: Long, length: Int): MediaChunk {
        if (offset < 0 || length <= 0 || length > MEDIA_UPLOAD_CHUNK_SIZE) throw MediaConflictException("Invalid media range")
        val row = dataSource.connection.use { connection -> connection.mediaForRead(mediaId, accountId, deviceId) }
        if (row.status != "READY") throw MediaNotReadyException()
        if (offset > row.expectedSize) throw MediaConflictException("Media range exceeds size")
        val count = minOf(length.toLong(), row.expectedSize - offset).toInt()
        return MediaChunk(store.read(mediaId, offset, count), row.expectedSize)
    }

    override fun delete(accountId: UUID, deviceId: UUID, mediaId: UUID) {
        requireActiveDevice(accountId, deviceId)
        dataSource.transaction { connection ->
            val owner = connection.queryOne(
                "SELECT owner_account_id FROM media_objects WHERE id = ? FOR UPDATE",
                { it.getObject(1, UUID::class.java) },
                mediaId,
            ) ?: throw MediaNotFoundException()
            if (owner != accountId) throw MediaAccessException()
            connection.execute(
                "UPDATE media_objects SET status = 'DELETED', deleted_at = ? WHERE id = ?",
                clock.instant(),
                mediaId,
            )
            store.delete(mediaId)
        }
    }

    override fun deleteExpired(): Int {
        val now = clock.instant()
        val ids = dataSource.connection.use { connection ->
            connection.queryAll(
                "SELECT id FROM media_objects WHERE expires_at <= ? AND deleted_at IS NULL",
                { it.getObject(1, UUID::class.java) },
                now,
            )
        }
        ids.forEach { mediaId ->
            dataSource.transaction { connection ->
                connection.execute(
                    "UPDATE media_objects SET status = 'DELETED', deleted_at = ? WHERE id = ? AND deleted_at IS NULL",
                    now,
                    mediaId,
                )
                store.delete(mediaId)
            }
        }
        return ids.size
    }

    private fun requireActiveDevice(accountId: UUID, deviceId: UUID) {
        dataSource.connection.use { connection ->
            val active = connection.queryOne(
                "SELECT 1 FROM devices WHERE id = ? AND account_id = ? AND revoked_at IS NULL",
                { it.getInt(1) },
                deviceId,
                accountId,
            ) != null
            if (!active) throw MediaAccessException()
        }
    }

    private fun Connection.mediaForRead(mediaId: UUID, accountId: UUID, deviceId: UUID): MediaRow {
        val active = queryOne(
            "SELECT 1 FROM devices WHERE id = ? AND account_id = ? AND revoked_at IS NULL",
            { it.getInt(1) },
            deviceId,
            accountId,
        ) != null
        if (!active) throw MediaAccessException()
        val row = queryOne("$mediaSelect WHERE id = ?", ::mediaRow, mediaId) ?: throw MediaNotFoundException()
        if (row.deletedAt != null || row.expiresAt <= clock.instant() || row.status == "DELETED") {
            throw MediaNotFoundException()
        }
        if (row.ownerAccountId != accountId) {
            val recipient = queryOne(
                "SELECT 1 FROM media_recipients WHERE media_id = ? AND device_id = ?",
                { it.getInt(1) },
                mediaId,
                deviceId,
            ) != null
            if (!recipient) throw MediaAccessException()
        }
        return row
    }

    private data class MediaRow(
        val id: UUID,
        val ownerAccountId: UUID,
        val expectedSize: Long,
        val uploadedSize: Long,
        val sha256: String,
        val status: String,
        val expiresAt: Instant,
        val deletedAt: Instant?,
    ) {
        fun toResponse() = MediaStatusResponse(
            mediaId = id.toString(),
            uploadedBytes = uploadedSize,
            expectedBytes = expectedSize,
            ready = status == "READY",
            expiresAt = expiresAt.toEpochMilli(),
        )
    }

    private companion object {
        const val mediaSelect = """
            SELECT id, owner_account_id, expected_size, uploaded_size, ciphertext_sha256, status, expires_at, deleted_at
            FROM media_objects
        """

        fun mediaRow(rs: ResultSet) = MediaRow(
            id = rs.getObject("id", UUID::class.java),
            ownerAccountId = rs.getObject("owner_account_id", UUID::class.java),
            expectedSize = rs.getLong("expected_size"),
            uploadedSize = rs.getLong("uploaded_size"),
            sha256 = rs.getString("ciphertext_sha256"),
            status = rs.getString("status"),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
        )
    }
}

private fun <T> Connection.queryOne(sql: String, mapper: (ResultSet) -> T, vararg parameters: Any?): T? =
    prepareStatement(sql).use { statement ->
        statement.bind(parameters)
        statement.executeQuery().use { rs -> if (rs.next()) mapper(rs) else null }
    }

private fun <T> Connection.queryAll(sql: String, mapper: (ResultSet) -> T, vararg parameters: Any?): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(parameters)
        statement.executeQuery().use { rs -> buildList { while (rs.next()) add(mapper(rs)) } }
    }

private fun Connection.execute(sql: String, vararg parameters: Any?): Int = prepareStatement(sql).use { statement ->
    statement.bind(parameters)
    statement.executeUpdate()
}

private fun PreparedStatement.bind(parameters: Array<out Any?>) {
    parameters.forEachIndexed { index, value ->
        when (value) {
            is Instant -> setTimestamp(index + 1, Timestamp.from(value))
            else -> setObject(index + 1, value)
        }
    }
}
