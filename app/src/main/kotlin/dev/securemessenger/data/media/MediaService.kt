package dev.securemessenger.data.media

import dev.securemessenger.data.AuthRepository
import dev.securemessenger.data.e2ee.MediaDescriptor
import dev.securemessenger.data.local.MediaUploadDao
import dev.securemessenger.data.local.MediaUploadEntity
import dev.securemessenger.shared.MAX_MEDIA_BYTES
import dev.securemessenger.shared.MEDIA_UPLOAD_CHUNK_SIZE
import dev.securemessenger.shared.MediaStatusResponse
import dev.securemessenger.shared.MediaUploadRequest
import dev.securemessenger.shared.MediaUploadResponse
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

interface MediaApi {
    suspend fun create(request: MediaUploadRequest): MediaUploadResponse
    suspend fun status(mediaId: String): MediaStatusResponse
    suspend fun upload(mediaId: String, offset: Long, bytes: ByteArray): MediaStatusResponse
    suspend fun download(mediaId: String, start: Long, endInclusive: Long): ByteArray
    suspend fun delete(mediaId: String)
}

class RepositoryMediaApi(private val accounts: AuthRepository) : MediaApi {
    override suspend fun create(request: MediaUploadRequest): MediaUploadResponse = accounts.createMediaUpload(request)
    override suspend fun status(mediaId: String): MediaStatusResponse = accounts.mediaStatus(mediaId)
    override suspend fun upload(mediaId: String, offset: Long, bytes: ByteArray): MediaStatusResponse =
        accounts.uploadMediaChunk(mediaId, offset, bytes)
    override suspend fun download(mediaId: String, start: Long, endInclusive: Long): ByteArray =
        accounts.downloadMediaChunk(mediaId, start, endInclusive)
    override suspend fun delete(mediaId: String) = accounts.deleteMedia(mediaId)
}

data class PreparedMedia(
    val descriptor: MediaDescriptor,
    val encryptedFile: File,
)

data class MediaResumeResult(
    val dispatched: Int,
    val skipped: Int,
)

class MediaService(
    private val root: File,
    private val api: MediaApi,
    private val uploads: MediaUploadDao,
) {
    suspend fun prepare(
        inputFactory: () -> InputStream,
        fileName: String,
        mimeType: String,
        plaintextBytes: Long?,
        onProgress: (Long) -> Unit = {},
        onSourceConsumed: () -> Unit = {},
        destinationChatId: String? = null,
        recipientUsername: String? = null,
        caption: String? = null,
    ): PreparedMedia {
        val size = plaintextBytes ?: inputFactory().use { it.countBytes() }
        require(size >= 0 && MediaCipher.encryptedSize(size) <= MAX_MEDIA_BYTES) { "Media is too large" }
        val normalizedName = fileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "file" }.take(255)
        require(mimeType.length <= 127) { "MIME type is too long" }
        val key = MediaCipher.newKey()
        root.mkdirs()
        val file = File(root, "upload-${UUID.randomUUID()}.ciphertext")
        val encrypted = try {
            inputFactory().use { input ->
                file.outputStream().use { output -> MediaCipher.encrypt(input, output, key, size) }
            }
        } catch (error: Throwable) {
            onSourceConsumed()
            file.delete()
            throw error
        }
        onSourceConsumed()
        require(file.length() == MediaCipher.encryptedSize(size)) { "Encrypted size mismatch" }
        val created = try {
            api.create(MediaUploadRequest(file.length(), encrypted.ciphertextSha256))
        } catch (error: Throwable) {
            file.delete()
            throw error
        }
        val entity = MediaUploadEntity(
            mediaId = created.mediaId,
            encryptedPath = file.absolutePath,
            fileName = normalizedName,
            mimeType = mimeType,
            plaintextBytes = size,
            ciphertextSha256 = encrypted.ciphertextSha256,
            key = Base64.getEncoder().encodeToString(key),
            uploadedBytes = 0,
            createdAt = System.currentTimeMillis(),
            destinationChatId = destinationChatId,
            recipientUsername = recipientUsername,
            caption = caption,
        )
        uploads.upsert(entity)
        onProgress(0)
        return upload(entity, onProgress)
    }

    suspend fun resumeUploads(): List<PreparedMedia> = uploads.pending().map { entity -> upload(entity) }

    suspend fun resumePending(dispatch: suspend (MediaUploadEntity, MediaDescriptor) -> Unit): MediaResumeResult {
        var sent = 0
        var skipped = 0
        uploads.pending().forEach { upload ->
            if (upload.destinationChatId == null) {
                skipped++
                return@forEach
            }
            val prepared = upload(upload)
            dispatch(upload, prepared.descriptor)
            sent++
        }
        return MediaResumeResult(sent, skipped)
    }

    suspend fun upload(entity: MediaUploadEntity, onProgress: (Long) -> Unit = {}): PreparedMedia {
        val file = File(entity.encryptedPath)
        require(file.isFile) { "Encrypted upload is missing" }
        var current = entity
        var status = api.status(current.mediaId)
        if (status.expectedBytes != file.length()) throw MediaIntegrityException("Upload size differs from server")
        if (!status.ready) {
            RandomAccessFile(file, "r").use { input ->
                var offset = status.uploadedBytes
                require(offset >= 0 && offset <= file.length()) { "Invalid server upload offset" }
                while (offset < file.length()) {
                    input.seek(offset)
                    val count = minOf(MEDIA_UPLOAD_CHUNK_SIZE.toLong(), file.length() - offset).toInt()
                    val bytes = ByteArray(count)
                    input.readFully(bytes)
                    val response = api.upload(current.mediaId, offset, bytes)
                    require(response.uploadedBytes == offset + count) { "Server did not acknowledge the full chunk" }
                    offset = response.uploadedBytes
                    current = current.copy(uploadedBytes = offset)
                    uploads.upsert(current)
                    onProgress(offset)
                    status = response
                }
            }
        }
        if (!status.ready) throw MediaIntegrityException("Server did not complete the upload")
        val ready = current.copy(uploadedBytes = file.length())
        uploads.upsert(ready)
        onProgress(file.length())
        return PreparedMedia(descriptor(ready), file)
    }

    suspend fun download(descriptor: MediaDescriptor, onProgress: (Long) -> Unit = {}): File {
        val expectedSize = MediaCipher.encryptedSize(descriptor.plaintextBytes)
        require(expectedSize <= MAX_MEDIA_BYTES) { "Media is too large" }
        root.mkdirs()
        val file = File(root, "download-${descriptor.mediaId}.ciphertext")
        if (file.exists() && file.length() > expectedSize) file.delete()
        var offset = if (file.exists()) file.length() else 0L
        while (offset < expectedSize) {
            val end = minOf(offset + MEDIA_UPLOAD_CHUNK_SIZE - 1L, expectedSize - 1)
            val bytes = api.download(descriptor.mediaId, offset, end)
            require(bytes.isNotEmpty() && offset + bytes.size <= expectedSize) { "Invalid media download range" }
            file.appendBytes(bytes)
            offset += bytes.size
            onProgress(offset)
        }
        if (file.length() != expectedSize || sha256(file) != descriptor.ciphertextSha256) {
            file.delete()
            throw MediaIntegrityException("Downloaded media hash differs")
        }
        return file
    }

    suspend fun reader(descriptor: MediaDescriptor): SeekableMediaReader {
        val file = existingCiphertext(descriptor) ?: download(descriptor)
        return SeekableMediaReader(file, decodeKey(descriptor))
    }

    suspend fun readBytes(descriptor: MediaDescriptor, maximumBytes: Int = 32 * 1024 * 1024): ByteArray {
        require(descriptor.plaintextBytes <= maximumBytes) { "Media is too large for memory preview" }
        val file = existingCiphertext(descriptor) ?: download(descriptor)
        val output = ByteArrayOutputStream(descriptor.plaintextBytes.toInt())
        file.inputStream().use { input -> MediaCipher.decrypt(input, output, decodeKey(descriptor)) }
        return output.toByteArray()
    }

    suspend fun decryptTo(descriptor: MediaDescriptor, output: OutputStream) {
        val file = existingCiphertext(descriptor) ?: download(descriptor)
        file.inputStream().use { input -> MediaCipher.decrypt(input, output, decodeKey(descriptor)) }
    }

    suspend fun existingCiphertext(descriptor: MediaDescriptor): File? {
        val download = File(root, "download-${descriptor.mediaId}.ciphertext")
        if (download.isFile && download.length() == MediaCipher.encryptedSize(descriptor.plaintextBytes) && sha256(download) == descriptor.ciphertextSha256) {
            return download
        }
        val upload = uploads.get(descriptor.mediaId) ?: return null
        val file = File(upload.encryptedPath)
        return file.takeIf {
            it.isFile && it.length() == MediaCipher.encryptedSize(descriptor.plaintextBytes) && sha256(it) == descriptor.ciphertextSha256
        }
    }

    suspend fun deleteUpload(mediaId: String) {
        val entity = uploads.get(mediaId)
        api.delete(mediaId)
        uploads.delete(mediaId)
        entity?.let { File(it.encryptedPath).delete() }
    }

    private fun descriptor(entity: MediaUploadEntity) = MediaDescriptor(
        mediaId = entity.mediaId,
        key = entity.key,
        ciphertextSha256 = entity.ciphertextSha256,
        fileName = entity.fileName,
        mimeType = entity.mimeType,
        plaintextBytes = entity.plaintextBytes,
    )

    private fun decodeKey(descriptor: MediaDescriptor): ByteArray = Base64.getDecoder().decode(descriptor.key)

    private fun InputStream.countBytes(): Long {
        var count = 0L
        val buffer = ByteArray(MediaCipher.CHUNK_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return count
            count += read
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(MediaCipher.CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
