package dev.securemessenger.data.media

import dev.securemessenger.data.AuthRepository
import dev.securemessenger.data.MessageRepository
import dev.securemessenger.shared.UserResponse
import javax.inject.Inject

class PendingMediaDispatcher @Inject constructor(
    private val media: MediaService,
    private val accounts: AuthRepository,
    private val messages: MessageRepository,
) {
    suspend fun resumePending(): MediaResumeResult = media.resumePending { upload, descriptor ->
        val destination = requireNotNull(upload.destinationChatId)
        when {
            destination.startsWith("direct:") -> messages.sendMedia(
                UserResponse(destination.removePrefix("direct:"), requireNotNull(upload.recipientUsername)),
                descriptor,
                upload.caption.orEmpty(),
            )
            destination.startsWith("group:") -> messages.sendGroupMedia(
                accounts.getGroup(destination.removePrefix("group:")),
                descriptor,
                upload.caption.orEmpty(),
            )
            else -> error("Unsupported media destination")
        }
    }
}
