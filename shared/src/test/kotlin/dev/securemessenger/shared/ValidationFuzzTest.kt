package dev.securemessenger.shared

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ValidationFuzzTest {
    @Test
    fun `normalizers are total and idempotent for malformed input`() {
        val random = Random(0xC0FFEE)
        val alphabet = "aA09_@.-+ !#$%&'*+/=?^_`{|}~,[]()<>:;\\\"\n\r\tАБВабв😀"
        val lengths = listOf(0, 1, 4, 5, 31, 32, 63, 64, 65, 127, 254, 255, 1024, 1025, 4096)
        repeat(1_000) { iteration ->
            val length = if (iteration % 5 == 0) lengths.random(random) else random.nextInt(0, 81)
            val input = buildString(length) { append(alphabet[random.nextInt(alphabet.length)]) }

            val username = runCatching { UsernameNormalizer.normalize(input) }
            username.exceptionOrNull()?.let { assertIs<ValidationException>(it) }
            username.getOrNull()?.let { normalized ->
                assertEquals(normalized, UsernameNormalizer.normalize(normalized))
            }

            val email = runCatching { EmailNormalizer.normalize(input) }
            email.exceptionOrNull()?.let { assertIs<ValidationException>(it) }
            email.getOrNull()?.let { normalized ->
                assertEquals(normalized, EmailNormalizer.normalize(normalized))
            }

            val expectedPasswordError = when {
                input.length < 12 -> PasswordError.TOO_SHORT
                input.length > 1024 -> PasswordError.TOO_LONG
                else -> null
            }
            assertEquals(expectedPasswordError, PasswordPolicy.validate(input))
            assertNull(runCatching { PasswordPolicy.validate(input) }.exceptionOrNull())
        }
    }
}
