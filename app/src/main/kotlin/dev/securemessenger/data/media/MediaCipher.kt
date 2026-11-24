package dev.securemessenger.data.media

import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MediaIntegrityException(message: String) : IllegalArgumentException(message)

data class MediaHeader(
    val plaintextBytes: Long,
    val chunkCount: Int,
    val baseNonce: ByteArray,
)

data class MediaEncryptionResult(
    val header: MediaHeader,
    val ciphertextSha256: String,
)

object MediaCipher {
    const val CHUNK_SIZE = 64 * 1024
    const val HEADER_SIZE = 32
    private const val TAG_SIZE = 16
    private val magic = byteArrayOf('S'.code.toByte(), 'M'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())

    fun newKey(): ByteArray = ByteArray(32).also(SecureRandom()::nextBytes)

    fun encryptedSize(plaintextBytes: Long): Long {
        val chunks = chunkCount(plaintextBytes)
        return HEADER_SIZE + plaintextBytes + chunks * TAG_SIZE
    }

    fun encrypt(input: InputStream, output: OutputStream, key: ByteArray, plaintextBytes: Long): MediaEncryptionResult {
        require(plaintextBytes >= 0) { "Negative plaintext size" }
        requireKey(key)
        val header = MediaHeader(plaintextBytes, chunkCount(plaintextBytes), ByteArray(12).also(SecureRandom()::nextBytes))
        val headerBytes = serializeHeader(header)
        val digest = MessageDigest.getInstance("SHA-256")
        output.write(headerBytes)
        digest.update(headerBytes)
        var remaining = plaintextBytes
        var index = 0
        while (remaining > 0) {
            val count = minOf(CHUNK_SIZE.toLong(), remaining).toInt()
            val plaintext = input.readFully(count)
            if (plaintext.size != count) throw EOFException("Plaintext ended before declared size")
            val encrypted = crypt(Cipher.ENCRYPT_MODE, key, headerBytes, index, plaintext)
            output.write(encrypted)
            digest.update(encrypted)
            remaining -= count
            index++
        }
        if (input.read() != -1) throw IllegalArgumentException("Plaintext exceeds declared size")
        return MediaEncryptionResult(header, digest.digest().hex())
    }

    fun decrypt(input: InputStream, output: OutputStream, key: ByteArray) {
        requireKey(key)
        val headerBytes = input.readFully(HEADER_SIZE)
        if (headerBytes.size != HEADER_SIZE) throw MediaIntegrityException("Missing media header")
        val header = parseHeader(headerBytes)
        repeat(header.chunkCount) { index ->
            val plaintextSize = plaintextChunkSize(header, index)
            val encrypted = input.readFully(plaintextSize + TAG_SIZE)
            if (encrypted.size != plaintextSize + TAG_SIZE) throw MediaIntegrityException("Truncated media chunk")
            output.write(crypt(Cipher.DECRYPT_MODE, key, headerBytes, index, encrypted))
        }
        if (input.read() != -1) throw MediaIntegrityException("Trailing media ciphertext")
    }

    fun readHeader(input: RandomAccessFile): MediaHeader {
        val bytes = ByteArray(HEADER_SIZE)
        input.readFully(bytes)
        return parseHeader(bytes)
    }

    fun decryptChunk(key: ByteArray, header: MediaHeader, index: Int, encrypted: ByteArray): ByteArray {
        requireKey(key)
        return crypt(Cipher.DECRYPT_MODE, key, serializeHeader(header), index, encrypted)
    }

    fun expectedFrameLength(header: MediaHeader, index: Int): Int = plaintextChunkSize(header, index) + TAG_SIZE

    private fun crypt(mode: Int, key: ByteArray, header: ByteArray, index: Int, input: ByteArray): ByteArray {
        try {
            val nonce = header.copyOfRange(20, HEADER_SIZE)
            System.arraycopy(ByteBuffer.allocate(4).putInt(index).array(), 0, nonce, 8, 4)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            cipher.updateAAD(ByteBuffer.allocate(4).putInt(index).array())
            return cipher.doFinal(input)
        } catch (error: GeneralSecurityException) {
            throw MediaIntegrityException("Media authentication failed")
        }
    }

    private fun serializeHeader(header: MediaHeader): ByteArray {
        require(header.baseNonce.size == 12) { "Invalid media nonce" }
        return ByteBuffer.allocate(HEADER_SIZE)
            .put(magic)
            .putInt(CHUNK_SIZE)
            .putLong(header.plaintextBytes)
            .putInt(header.chunkCount)
            .put(header.baseNonce)
            .array()
    }

    private fun parseHeader(bytes: ByteArray): MediaHeader {
        if (bytes.size != HEADER_SIZE || !bytes.copyOfRange(0, 4).contentEquals(magic)) {
            throw MediaIntegrityException("Invalid media header")
        }
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(4)
        val chunkSize = buffer.int
        val plaintextBytes = buffer.long
        val chunkCount = buffer.int
        val nonce = ByteArray(12)
        buffer.get(nonce)
        if (chunkSize != CHUNK_SIZE || plaintextBytes < 0 || chunkCount != chunkCount(plaintextBytes)) {
            throw MediaIntegrityException("Invalid media dimensions")
        }
        return MediaHeader(plaintextBytes, chunkCount, nonce)
    }

    private fun plaintextChunkSize(header: MediaHeader, index: Int): Int {
        if (index < 0 || index >= header.chunkCount) throw MediaIntegrityException("Invalid media chunk index")
        return minOf(CHUNK_SIZE.toLong(), header.plaintextBytes - index.toLong() * CHUNK_SIZE).toInt()
    }

    private fun chunkCount(plaintextBytes: Long): Int {
        require(plaintextBytes >= 0 && plaintextBytes <= (Int.MAX_VALUE.toLong() * CHUNK_SIZE)) { "Media is too large" }
        return ((plaintextBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
    }

    private fun InputStream.readFully(size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val read = read(result, offset, size - offset)
            if (read < 0) return result.copyOf(offset)
            offset += read
        }
        return result
    }

    private fun requireKey(key: ByteArray) {
        require(key.size == 32) { "Media key must be 256 bits" }
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
}

class SeekableMediaReader(
    private val file: RandomAccessFile,
    private val key: ByteArray,
) : Closeable {
    constructor(file: java.io.File, key: ByteArray) : this(RandomAccessFile(file, "r"), key)

    val header: MediaHeader = try {
        MediaCipher.readHeader(file)
    } catch (error: Throwable) {
        file.close()
        throw error
    }
    val plaintextBytes: Long = header.plaintextBytes

    init {
        if (file.length() != MediaCipher.encryptedSize(plaintextBytes)) {
            file.close()
            throw MediaIntegrityException("Media size differs from header")
        }
    }

    @Synchronized
    fun read(position: Long, destination: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || offset < 0 || length < 0 || offset + length > destination.size) {
            throw IllegalArgumentException("Invalid media read range")
        }
        if (position >= plaintextBytes) return -1
        val count = minOf(length.toLong(), plaintextBytes - position).toInt()
        var written = 0
        while (written < count) {
            val absolute = position + written
            val index = (absolute / MediaCipher.CHUNK_SIZE).toInt()
            val inChunk = (absolute % MediaCipher.CHUNK_SIZE).toInt()
            val encryptedLength = MediaCipher.expectedFrameLength(header, index)
            file.seek(MediaCipher.HEADER_SIZE + index.toLong() * (MediaCipher.CHUNK_SIZE + 16))
            val encrypted = ByteArray(encryptedLength)
            file.readFully(encrypted)
            val plaintext = MediaCipher.decryptChunk(key, header, index, encrypted)
            val copyCount = minOf(count - written, plaintext.size - inChunk)
            System.arraycopy(plaintext, inChunk, destination, offset + written, copyCount)
            written += copyCount
        }
        return written
    }

    @Synchronized
    override fun close() = file.close()
}
