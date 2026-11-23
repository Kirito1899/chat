package dev.securemessenger.server.security

import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import java.security.Security

interface PasswordHasher {
    fun hash(password: String): String
    fun verify(password: String, encoded: String): Boolean
}

class Argon2PasswordHasher : PasswordHasher {
    private val encoder = Argon2PasswordEncoder(
        16,
        32,
        1,
        64 * 1024,
        3,
    )

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    override fun hash(password: String): String = requireNotNull(encoder.encode(password))

    override fun verify(password: String, encoded: String): Boolean = try {
        encoder.matches(password, encoded)
    } catch (_: IllegalArgumentException) {
        false
    }
}
