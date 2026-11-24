package dev.securemessenger.devtools

import java.sql.DriverManager

fun main() {
    val expectedText = requireNotNull(System.getenv("EXPECTED_TEXT")) { "EXPECTED_TEXT is required" }
    val minimumQueueSize = System.getenv("MIN_QUEUE_SIZE")?.toIntOrNull() ?: 1
    require(minimumQueueSize >= 1) { "MIN_QUEUE_SIZE must be positive" }
    val plaintext = expectedText.toByteArray(Charsets.UTF_8)
    require(plaintext.isNotEmpty()) { "EXPECTED_TEXT must not be empty" }

    DriverManager.getConnection(
        System.getenv("DATABASE_URL"),
        System.getenv("DATABASE_USER"),
        System.getenv("DATABASE_PASSWORD"),
    ).use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT ciphertext FROM message_queue WHERE expires_at > now()").use { rows ->
                var count = 0
                while (rows.next()) {
                    count++
                    check(!rows.getBytes("ciphertext").containsSequence(plaintext)) {
                        "Plaintext message content found in PostgreSQL"
                    }
                }
                check(count >= minimumQueueSize) {
                    "Expected at least $minimumQueueSize queued messages, found $count"
                }
                println("Verified $count queued ciphertext messages; plaintext is absent")
            }
        }
    }
}

private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
    sequence.size <= size && (0..size - sequence.size).any { start ->
        sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
    }
