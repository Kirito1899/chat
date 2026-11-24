package dev.securemessenger.server.config

import dev.securemessenger.server.mail.SmtpConfig
import java.nio.file.Path
import java.util.Base64

data class ServerConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val tokenPepper: ByteArray,
    val adminToken: String,
    val smtp: SmtpConfig,
    val mediaPath: Path,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): ServerConfig {
            val pepper = environment["TOKEN_PEPPER_BASE64"]
                ?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
                ?: error("TOKEN_PEPPER_BASE64 is required")
            require(pepper.size >= 32) { "TOKEN_PEPPER_BASE64 must decode to at least 32 bytes" }
            val adminToken = environment["ADMIN_TOKEN"] ?: error("ADMIN_TOKEN is required")
            require(adminToken.length >= 32) { "ADMIN_TOKEN must contain at least 32 characters" }

            val smtpHost = environment["SMTP_HOST"] ?: "127.0.0.1"
            val remote = smtpHost !in setOf("localhost", "127.0.0.1", "::1")
            val smtp = SmtpConfig(
                host = smtpHost,
                port = environment["SMTP_PORT"]?.toIntOrNull() ?: 1025,
                username = environment["SMTP_USERNAME"],
                password = environment["SMTP_PASSWORD"],
                from = environment["SMTP_FROM"] ?: "messenger@localhost",
                startTls = environment["SMTP_STARTTLS"]?.toBooleanStrictOrNull() ?: remote,
                ssl = environment["SMTP_SSL"]?.toBooleanStrictOrNull() ?: false,
            )
            return ServerConfig(
                databaseUrl = environment["DATABASE_URL"] ?: "jdbc:postgresql://127.0.0.1:5432/secure_messenger",
                databaseUser = environment["DATABASE_USER"] ?: "messenger",
                databasePassword = environment["DATABASE_PASSWORD"] ?: error("DATABASE_PASSWORD is required"),
                tokenPepper = pepper,
                adminToken = adminToken,
                smtp = smtp,
                mediaPath = Path.of(environment["MEDIA_STORAGE_PATH"] ?: ".local/media").toAbsolutePath().normalize(),
            )
        }
    }
}
