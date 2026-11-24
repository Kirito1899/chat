package dev.securemessenger.shared

import kotlinx.serialization.json.Json
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GroupMediaApiTest {
    @Test
    fun `direct message remains compatible while media and group ids are optional`() {
        val request = SendMessageRequest(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            3,
            Base64.getEncoder().encodeToString(ByteArray(64) { 2 }),
        )
        val decoded = Json.decodeFromString<SendMessageRequest>(Json.encodeToString(request))
        assertNull(decoded.mediaId)
        request.copy(mediaId = UUID.randomUUID().toString()).validate()

        val incoming = IncomingMessage(
            request.messageId,
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            3,
            request.ciphertext,
            1,
        )
        assertNull(incoming.groupId)
        assertNull(incoming.groupMessageId)
    }

    @Test
    fun `group and media requests reject malformed identifiers and sizes`() {
        val envelope = SendMessageRequest(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            3,
            Base64.getEncoder().encodeToString(ByteArray(64) { 2 }),
        )
        assertFailsWith<ValidationException> {
            SendGroupMessageRequest("invalid", envelope).validate()
        }
        assertFailsWith<ValidationException> {
            MediaUploadRequest(0, "0".repeat(64)).validate()
        }
        assertFailsWith<ValidationException> {
            MediaUploadRequest(1, "not-a-hash").validate()
        }
        MediaUploadRequest(MAX_MEDIA_BYTES, "a".repeat(64)).validate()
    }
}
