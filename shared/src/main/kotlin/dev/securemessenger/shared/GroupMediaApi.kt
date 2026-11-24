package dev.securemessenger.shared

import kotlinx.serialization.Serializable

@Serializable
data class GroupMemberResponse(
    val accountId: String,
    val username: String,
    val role: String,
)

@Serializable
data class GroupResponse(
    val groupId: String,
    val ownerAccountId: String,
    val members: List<GroupMemberResponse>,
)

@Serializable
data class GroupListResponse(val groups: List<GroupResponse>)

@Serializable
data class CreateGroupRequest(val memberUsernames: List<String>)

@Serializable
data class AddGroupMembersRequest(val memberUsernames: List<String>)

@Serializable
data class SendGroupMessageRequest(
    val groupMessageId: String,
    val envelope: SendMessageRequest,
) {
    fun validate() {
        groupMessageId.requireUuid()
        envelope.validate()
    }
}

@Serializable
data class MediaUploadRequest(
    val expectedBytes: Long,
    val ciphertextSha256: String,
) {
    fun validate() {
        requireValid(expectedBytes > 0 && expectedBytes <= MAX_MEDIA_BYTES, "INVALID_MEDIA_SIZE")
        requireValid(Regex("[0-9a-f]{64}").matches(ciphertextSha256), "INVALID_MEDIA_HASH")
    }
}

@Serializable
data class MediaUploadResponse(
    val mediaId: String,
    val chunkSize: Int,
    val expiresAt: Long,
)

@Serializable
data class MediaStatusResponse(
    val mediaId: String,
    val uploadedBytes: Long,
    val expectedBytes: Long,
    val ready: Boolean,
    val expiresAt: Long,
)

const val MAX_MEDIA_BYTES: Long = 512L * 1024 * 1024
const val MEDIA_UPLOAD_CHUNK_SIZE: Int = 1024 * 1024
const val MEDIA_TTL_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

private fun requireValid(condition: Boolean, code: String) {
    if (!condition) throw ValidationException(code)
}
