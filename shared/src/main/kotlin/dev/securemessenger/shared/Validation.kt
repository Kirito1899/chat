package dev.securemessenger.shared

class ValidationException(val code: String) : IllegalArgumentException(code)

object UsernameNormalizer {
    private val allowed = Regex("[a-z][a-z0-9_]{4,31}")

    fun normalize(raw: String): String {
        val normalized = raw.trim().removePrefix("@").lowercase()
        if (!allowed.matches(normalized)) {
            throw ValidationException("INVALID_USERNAME")
        }
        return normalized
    }
}

enum class PasswordError {
    TOO_SHORT,
    TOO_LONG,
}

object PasswordPolicy {
    fun validate(password: String): PasswordError? = when {
        password.length < 12 -> PasswordError.TOO_SHORT
        password.length > 1024 -> PasswordError.TOO_LONG
        else -> null
    }
}

object EmailNormalizer {
    private val email = Regex("[a-z0-9!#$%&'*+/=?^_`{|}~.-]+@[a-z0-9.-]+")

    fun normalize(raw: String): String {
        val normalized = raw.trim().lowercase()
        val local = normalized.substringBefore('@')
        val domain = normalized.substringAfter('@', "")
        val valid = normalized.length <= 254 &&
            local.length in 1..64 &&
            domain.length in 1..253 &&
            email.matches(normalized) &&
            !local.startsWith('.') &&
            !local.endsWith('.') &&
            ".." !in normalized &&
            !domain.startsWith('.') &&
            !domain.endsWith('.') &&
            !domain.startsWith('-') &&
            !domain.endsWith('-')
        if (!valid) {
            throw ValidationException("INVALID_EMAIL")
        }
        return normalized
    }
}
