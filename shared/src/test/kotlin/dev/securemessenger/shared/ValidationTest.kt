package dev.securemessenger.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ValidationTest {
    @Test
    fun `username is trimmed and normalized to lowercase`() {
        assertEquals("alice_01", UsernameNormalizer.normalize("  Alice_01  "))
    }

    @Test
    fun `username rejects unicode lookalikes and punctuation`() {
        listOf("алиса", "alice-admin", "alice admin", "_alice", "1alice").forEach {
            assertFailsWith<ValidationException>(it) {
                UsernameNormalizer.normalize(it)
            }
        }
    }

    @Test
    fun `username enforces inclusive length boundaries`() {
        assertEquals("a2345", UsernameNormalizer.normalize("a2345"))
        assertEquals("a" + "2".repeat(31), UsernameNormalizer.normalize("a" + "2".repeat(31)))
        assertFailsWith<ValidationException> { UsernameNormalizer.normalize("a234") }
        assertFailsWith<ValidationException> { UsernameNormalizer.normalize("a" + "2".repeat(32)) }
    }

    @Test
    fun `password accepts documented boundaries only`() {
        assertEquals(PasswordError.TOO_SHORT, PasswordPolicy.validate("a".repeat(11)))
        assertNull(PasswordPolicy.validate("a".repeat(12)))
        assertNull(PasswordPolicy.validate("a".repeat(1024)))
        assertEquals(PasswordError.TOO_LONG, PasswordPolicy.validate("a".repeat(1025)))
    }

    @Test
    fun `email normalization trims and lowercases without rewriting local part semantics`() {
        assertEquals("user+tag@example.com", EmailNormalizer.normalize("  User+Tag@Example.COM "))
    }
}
