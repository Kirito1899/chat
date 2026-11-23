package dev.securemessenger.data.e2ee

import kotlinx.serialization.Serializable

@Serializable
data class ChatPayload(
    val kind: String,
    val messageId: String,
    val body: String = "",
    val createdAt: Long,
) {
    companion object {
        const val TEXT = "TEXT"
        const val READ_RECEIPT = "READ_RECEIPT"
    }
}
