package dev.securemessenger.server.security

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class GeneratedCredential(val raw: String, val hash: String)

class TokenService(pepper: ByteArray) {
    private val random = SecureRandom()
    private val pepper = pepper.copyOf()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    init {
        require(this.pepper.size >= 32) { "Token pepper must contain at least 32 bytes" }
    }

    fun newToken(): GeneratedCredential {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val raw = encoder.encodeToString(bytes)
        return GeneratedCredential(raw, hashToken(raw))
    }

    fun newEmailCode(): GeneratedCredential {
        val raw = "%08d".format(random.nextInt(100_000_000))
        return GeneratedCredential(raw, hashEmailCode(raw))
    }

    fun hashToken(raw: String): String = encoder.encodeToString(
        MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8)),
    )

    fun hashEmailCode(raw: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pepper, "HmacSHA256"))
        return encoder.encodeToString(mac.doFinal(raw.toByteArray(Charsets.UTF_8)))
    }

    fun secureEquals(first: String, second: String): Boolean = MessageDigest.isEqual(
        first.toByteArray(Charsets.UTF_8),
        second.toByteArray(Charsets.UTF_8),
    )
}
