package dev.securemessenger.data.media

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class MediaCipherTest {
    private val roots = mutableListOf<Path>()

    @AfterTest
    fun cleanup() {
        roots.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `encryption round trips boundaries and supports random access`() {
        listOf(0, 1, MediaCipher.CHUNK_SIZE - 1, MediaCipher.CHUNK_SIZE, MediaCipher.CHUNK_SIZE + 1, 150_000).forEach { size ->
            val plaintext = ByteArray(size) { (it % 251).toByte() }
            val encrypted = encrypt(plaintext)
            val decrypted = encrypted.file.inputStream().use { input ->
                java.io.ByteArrayOutputStream().also { output ->
                    MediaCipher.decrypt(input, output, encrypted.key)
                }.toByteArray()
            }
            assertContentEquals(plaintext, decrypted, "Round trip failed for $size bytes")
            SeekableMediaReader(encrypted.file, encrypted.key).use { reader ->
                listOf(0, size / 2, size - 1).filter { it >= 0 && it < size }.forEach { position ->
                    val actual = ByteArray(minOf(1000, size - position))
                    val read = reader.read(position.toLong(), actual, 0, actual.size)
                    assertEquals(actual.size, read)
                    assertContentEquals(plaintext.copyOfRange(position, position + actual.size), actual)
                }
            }
        }
    }

    @Test
    fun `tampered ciphertext is rejected by GCM`() {
        val encrypted = encrypt(ByteArray(MediaCipher.CHUNK_SIZE + 100) { 7 })
        val bytes = encrypted.file.readBytes()
        bytes[MediaCipher.HEADER_SIZE + 20] = (bytes[MediaCipher.HEADER_SIZE + 20].toInt() xor 1).toByte()
        encrypted.file.writeBytes(bytes)

        assertFailsWith<MediaIntegrityException> {
            MediaCipher.decrypt(encrypted.file.inputStream(), java.io.ByteArrayOutputStream(), encrypted.key)
        }
        assertFailsWith<MediaIntegrityException> {
            SeekableMediaReader(encrypted.file, encrypted.key).use { reader ->
                reader.read(0, ByteArray(32), 0, 32)
            }
        }
    }

    @Test
    fun `truncated reordered and wrong-key media is rejected`() {
        val encrypted = encrypt(ByteArray(MediaCipher.CHUNK_SIZE * 2) { 9 })
        val bytes = encrypted.file.readBytes()
        encrypted.file.writeBytes(bytes.copyOf(bytes.size - 1))
        assertFailsWith<MediaIntegrityException> { SeekableMediaReader(encrypted.file, encrypted.key) }

        val reordered = bytes.copyOf()
        val firstFrame = reordered.copyOfRange(MediaCipher.HEADER_SIZE, MediaCipher.HEADER_SIZE + MediaCipher.CHUNK_SIZE + 16)
        val secondFrame = reordered.copyOfRange(MediaCipher.HEADER_SIZE + MediaCipher.CHUNK_SIZE + 16, reordered.size)
        System.arraycopy(secondFrame, 0, reordered, MediaCipher.HEADER_SIZE, secondFrame.size)
        System.arraycopy(firstFrame, 0, reordered, MediaCipher.HEADER_SIZE + secondFrame.size, firstFrame.size)
        encrypted.file.writeBytes(reordered)
        assertFailsWith<MediaIntegrityException> {
            MediaCipher.decrypt(encrypted.file.inputStream(), java.io.ByteArrayOutputStream(), encrypted.key)
        }
        assertFailsWith<MediaIntegrityException> {
            MediaCipher.decrypt(encrypted.file.inputStream(), java.io.ByteArrayOutputStream(), MediaCipher.newKey())
        }
    }

    @Test
    fun `plaintext is absent and each encryption uses a new nonce`() {
        val plaintext = "A plaintext media marker that must not be stored".toByteArray()
        val first = encrypt(plaintext)
        val second = encrypt(plaintext)

        assertFalse(String(first.file.readBytes(), Charsets.ISO_8859_1).contains(String(plaintext, Charsets.ISO_8859_1)))
        assertNotEquals(first.result.header.baseNonce.toList(), second.result.header.baseNonce.toList())
        assertNotEquals(first.result.ciphertextSha256, second.result.ciphertextSha256)
    }

    private fun encrypt(plaintext: ByteArray): EncryptedFixture {
        val root = Files.createTempDirectory("media-cipher-test").also(roots::add)
        val file = root.resolve("payload.ciphertext").toFile()
        val key = MediaCipher.newKey()
        val result = plaintext.inputStream().use { input ->
            file.outputStream().use { output -> MediaCipher.encrypt(input, output, key, plaintext.size.toLong()) }
        }
        return EncryptedFixture(file, key, result)
    }

    private data class EncryptedFixture(
        val file: java.io.File,
        val key: ByteArray,
        val result: MediaEncryptionResult,
    )
}
