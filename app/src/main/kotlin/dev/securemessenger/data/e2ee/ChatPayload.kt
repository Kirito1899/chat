package dev.securemessenger.data.e2ee

import kotlinx.serialization.Serializable

@Serializable
data class MediaDescriptor(
    val mediaId: String,
    val key: String,
    val ciphertextSha256: String,
    val fileName: String,
    val mimeType: String,
    val plaintextBytes: Long,
    val durationMs: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
data class ChatPayload(
    val kind: String,
    val messageId: String,
    val body: String = "",
    val createdAt: Long,
    val groupId: String? = null,
    val media: MediaDescriptor? = null,
) {
    companion object {
        const val TEXT = "TEXT"
        const val READ_RECEIPT = "READ_RECEIPT"
        const val GROUP_TEXT = "GROUP_TEXT"
        const val GROUP_INFO = "GROUP_INFO"
        const val GROUP_READ_RECEIPT = "GROUP_READ_RECEIPT"
        const val MEDIA = "MEDIA"
        const val GROUP_MEDIA = "GROUP_MEDIA"

        val receipts = setOf(READ_RECEIPT, GROUP_READ_RECEIPT)
        val mediaKinds = setOf(MEDIA, GROUP_MEDIA)
    }
}
