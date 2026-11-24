package dev.securemessenger.devtools

import java.nio.file.Files
import java.nio.file.Path

fun main() {
    val root = Path.of(requireNotNull(System.getenv("MEDIA_STORAGE_PATH")) { "MEDIA_STORAGE_PATH is required" })
    val forbidden = requireNotNull(System.getenv("EXPECTED_TEXT")) { "EXPECTED_TEXT is required" }.toByteArray(Charsets.UTF_8)
    require(forbidden.isNotEmpty()) { "EXPECTED_TEXT must not be empty" }
    var count = 0
    Files.list(root).use { files ->
        files.forEach { file ->
            if (Files.isRegularFile(file)) {
                count++
                val bytes = Files.readAllBytes(file)
                check(!bytes.containsSequence(forbidden)) { "Plaintext media content found in server storage" }
            }
        }
    }
    check(count > 0) { "No encrypted media files found" }
    println("Verified $count encrypted media files; plaintext is absent")
}

private fun ByteArray.containsSequence(sequence: ByteArray): Boolean =
    sequence.size <= size && (0..size - sequence.size).any { start ->
        sequence.indices.all { offset -> this[start + offset] == sequence[offset] }
    }
