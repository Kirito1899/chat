package dev.securemessenger.data.media

import dev.securemessenger.data.local.MediaUploadDao
import dev.securemessenger.data.local.MediaUploadEntity
import dev.securemessenger.shared.MEDIA_UPLOAD_CHUNK_SIZE
import dev.securemessenger.shared.MediaStatusResponse
import dev.securemessenger.shared.MediaUploadRequest
import dev.securemessenger.shared.MediaUploadResponse
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MediaServiceTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `upload resumes from server offset and preserves key in SQLCipher model`() = runTest {
        val root = root()
        val plaintext = ByteArray(MEDIA_UPLOAD_CHUNK_SIZE + 37_000) { (it % 239).toByte() }
        val api = FakeMediaApi().apply { failFirstUpload = true }
        val dao = FakeMediaUploadDao()
        val service = MediaService(root.toFile(), api, dao)

        assertFailsWith<IOException> {
            service.prepare({ plaintext.inputStream() }, "voice.ogg", "audio/ogg", plaintext.size.toLong())
        }
        val pending = assertNotNull(dao.get(api.mediaId))
        assertEquals(0, pending.uploadedBytes)
        assertEquals(MEDIA_UPLOAD_CHUNK_SIZE.toLong(), api.status(api.mediaId).uploadedBytes)

        val prepared = service.resumeUploads().single()
        assertEquals(plaintext.size.toLong(), prepared.descriptor.plaintextBytes)
        assertEquals(pending.key, prepared.descriptor.key)
        assertContentEquals(plaintext, service.readBytes(prepared.descriptor))
    }

    @Test
    fun `pending resume preserves destination and dispatches only once`() = runTest {
        val plaintext = ByteArray(128) { 5 }
        val api = FakeMediaApi()
        val dao = FakeMediaUploadDao()
        val service = MediaService(root().toFile(), api, dao)
        service.prepare(
            { plaintext.inputStream() },
            "note.txt",
            "text/plain",
            plaintext.size.toLong(),
            destinationChatId = "direct:account",
            recipientUsername = "friend",
            caption = "caption",
        )
        val destinations = mutableListOf<String>()
        val result = service.resumePending { upload, _ ->
            destinations += "${upload.destinationChatId}:${upload.recipientUsername}:${upload.caption}"
            dao.tryMarkDispatched(api.mediaId, "message-1")
        }
        val repeated = service.resumePending { _, _ -> destinations += "duplicate" }

        assertEquals(1, result.dispatched)
        assertEquals(0, result.skipped)
        assertEquals(listOf("direct:account:friend:caption"), destinations)
        assertEquals(0, repeated.dispatched)
        assertTrue(dao.get(api.mediaId)?.dispatched == true)
    }

    @Test
    fun `download resumes locally and tampering is rejected`() = runTest {
        val plaintext = ByteArray(MEDIA_UPLOAD_CHUNK_SIZE + 100) { 11 }
        val api = FakeMediaApi()
        val uploadDao = FakeMediaUploadDao()
        val uploader = MediaService(root().toFile(), api, uploadDao)
        val prepared = uploader.prepare({ plaintext.inputStream() }, "photo.jpg", "image/jpeg", plaintext.size.toLong())
        val downloadDao = FakeMediaUploadDao()
        val downloader = MediaService(root().toFile(), api, downloadDao)
        api.failSecondDownload = true

        assertFailsWith<IOException> { downloader.download(prepared.descriptor) }
        val ciphertext = downloader.download(prepared.descriptor)
        assertEquals(api.content.size.toLong(), ciphertext.length())
        assertContentEquals(plaintext, downloader.readBytes(prepared.descriptor))

        api.content[100] = (api.content[100].toInt() xor 1).toByte()
        ciphertext.delete()
        assertFailsWith<MediaIntegrityException> { downloader.readBytes(prepared.descriptor) }
    }

    private fun root(): Path = Files.createTempDirectory("media-service-test").also(roots::add)

    private class FakeMediaUploadDao : MediaUploadDao {
        private val items = linkedMapOf<String, MediaUploadEntity>()

        override suspend fun upsert(item: MediaUploadEntity) {
            items[item.mediaId] = item
        }

        override suspend fun get(mediaId: String): MediaUploadEntity? = items[mediaId]
        override suspend fun all(): List<MediaUploadEntity> = items.values.toList()
        override suspend fun pending(): List<MediaUploadEntity> = items.values.filter { !it.dispatched }
        override suspend fun tryMarkDispatched(mediaId: String, messageId: String): Int {
            val current = items[mediaId] ?: return 0
            if (current.dispatched) return 0
            items[mediaId] = current.copy(dispatched = true, messageId = messageId)
            return 1
        }
        override suspend fun delete(mediaId: String) {
            items.remove(mediaId)
        }
    }

    private class FakeMediaApi : MediaApi {
        val mediaId = UUID.randomUUID().toString()
        private var expected = 0L
        private var uploaded = 0L
        lateinit var content: ByteArray
        var failFirstUpload = false
        var failSecondDownload = false
        private var downloadCalls = 0

        override suspend fun create(request: MediaUploadRequest): MediaUploadResponse {
            expected = request.expectedBytes
            content = ByteArray(expected.toInt())
            return MediaUploadResponse(mediaId, MEDIA_UPLOAD_CHUNK_SIZE, Long.MAX_VALUE)
        }

        override suspend fun status(mediaId: String) = MediaStatusResponse(mediaId, uploaded, expected, uploaded == expected, Long.MAX_VALUE)

        override suspend fun upload(mediaId: String, offset: Long, bytes: ByteArray): MediaStatusResponse {
            System.arraycopy(bytes, 0, content, offset.toInt(), bytes.size)
            uploaded = maxOf(uploaded, offset + bytes.size)
            if (failFirstUpload) {
                failFirstUpload = false
                throw IOException("Response lost after server commit")
            }
            return status(mediaId)
        }

        override suspend fun download(mediaId: String, start: Long, endInclusive: Long): ByteArray {
            downloadCalls++
            if (failSecondDownload && downloadCalls == 2) throw IOException("Download interrupted")
            return content.copyOfRange(start.toInt(), endInclusive.toInt() + 1)
        }

        override suspend fun delete(mediaId: String) = Unit
    }
}
