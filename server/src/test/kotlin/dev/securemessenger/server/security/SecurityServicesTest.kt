package dev.securemessenger.server.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SecurityServicesTest {
    private val pepper = ByteArray(32) { it.toByte() }
    private val tokens = TokenService(pepper)

    @Test
    fun `password hasher uses argon2id and rejects wrong password`() {
        val hasher = Argon2PasswordHasher()
        val encoded = hasher.hash("a-secure-password")
        assertTrue(encoded.contains("argon2id"))
        assertFalse(encoded.contains("a-secure-password"))
        assertTrue(hasher.verify("a-secure-password", encoded))
        assertFalse(hasher.verify("wrong-password", encoded))
    }

    @Test
    fun `tokens are high entropy unique and only hashes are deterministic`() {
        val first = tokens.newToken()
        val second = tokens.newToken()
        assertNotEquals(first.raw, second.raw)
        assertNotEquals(first.hash, second.hash)
        assertEquals(first.hash, tokens.hashToken(first.raw))
        assertTrue(first.raw.length >= 40)
    }

    @Test
    fun `short email code is eight digits and hmac depends on pepper`() {
        val code = tokens.newEmailCode()
        assertTrue(code.raw.matches(Regex("[0-9]{8}")))
        assertEquals(code.hash, tokens.hashEmailCode(code.raw))
        assertNotEquals(code.hash, TokenService(ByteArray(32) { 1 }).hashEmailCode(code.raw))
    }
}
