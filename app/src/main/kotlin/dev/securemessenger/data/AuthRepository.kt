package dev.securemessenger.data

import dev.securemessenger.data.local.SessionStore
import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.data.remote.MessengerApi
import dev.securemessenger.shared.AddGroupMembersRequest
import dev.securemessenger.shared.CreateGroupRequest
import dev.securemessenger.shared.DeliveredMessagesResponse
import dev.securemessenger.shared.DeliveryResponse
import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.GroupListResponse
import dev.securemessenger.shared.GroupResponse
import dev.securemessenger.shared.MediaStatusResponse
import dev.securemessenger.shared.MediaUploadRequest
import dev.securemessenger.shared.MediaUploadResponse
import dev.securemessenger.shared.SendGroupMessageRequest
import dev.securemessenger.shared.MessageListResponse
import dev.securemessenger.shared.PreKeyBundleResponse
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.EmailCodeRequest
import dev.securemessenger.shared.LoginRequest
import dev.securemessenger.shared.LogoutRequest
import dev.securemessenger.shared.PasswordResetRequest
import dev.securemessenger.shared.RefreshRequest
import dev.securemessenger.shared.RegisterRequest
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse
import dev.securemessenger.shared.UsernameNormalizer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AuthRepository(
    private val api: MessengerApi,
    private val sessions: SessionStore,
) : AccountRepository {
    private val refreshMutex = Mutex()
    override suspend fun restore(): TokenResponse? = sessions.read()

    override suspend fun register(invite: String, email: String, username: String, password: String) =
        api.register(RegisterRequest(invite, email, username, password))

    override suspend fun verifyEmail(email: String, code: String) {
        api.verifyEmail(EmailCodeRequest(email, code))
    }

    override suspend fun resendVerification(email: String) {
        api.resendVerification(email)
    }

    override suspend fun login(email: String, password: String): TokenResponse {
        val session = try {
            api.login(LoginRequest(email, password, sessions.deviceId()))
        } catch (error: ApiException) {
            if (error.code != "DEVICE_LIMIT") throw error
            api.login(LoginRequest(email, password, sessions.resetDeviceId()))
        }
        sessions.save(session)
        return session
    }

    override suspend fun recover(email: String) {
        api.recover(email)
    }

    override suspend fun resetPassword(email: String, code: String, newPassword: String) {
        api.resetPassword(PasswordResetRequest(email, code, newPassword))
    }

    override suspend fun searchByUsername(username: String): UserResponse {
        val normalized = UsernameNormalizer.normalize(username)
        return authorized { api.findByUsername(normalized, it) }
    }

    override suspend fun logout() {
        val session = sessions.read()
        try {
            if (session != null) {
                try {
                    api.logout(LogoutRequest(session.refreshToken))
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                }
            }
        } finally {
            sessions.clear()
        }
    }

    suspend fun uploadKeys(request: DeviceKeysUpload) = authorized { api.uploadKeys(request, it) }

    suspend fun fetchPreKeyBundle(username: String): PreKeyBundleResponse = authorized { api.fetchPreKeyBundle(username, it) }

    suspend fun sendMessage(request: SendMessageRequest) = authorized { api.sendMessage(request, it) }

    suspend fun fetchMessages(): MessageListResponse = authorized { api.fetchMessages(it) }

    suspend fun markDelivered(messageId: String) = authorized { api.markDelivered(messageId, it) }

    suspend fun fetchDelivered(): DeliveredMessagesResponse = authorized { api.fetchDelivered(it) }

    suspend fun createGroup(memberUsernames: List<String>): GroupResponse =
        authorized { api.createGroup(CreateGroupRequest(memberUsernames), it) }

    suspend fun listGroups(): GroupListResponse = authorized { api.listGroups(it) }

    suspend fun getGroup(groupId: String): GroupResponse = authorized { api.getGroup(groupId, it) }

    suspend fun addGroupMembers(groupId: String, memberUsernames: List<String>): GroupResponse =
        authorized { api.addGroupMembers(groupId, AddGroupMembersRequest(memberUsernames), it) }

    suspend fun removeGroupMember(groupId: String, accountId: String): GroupResponse =
        authorized { api.removeGroupMember(groupId, accountId, it) }

    suspend fun sendGroupMessage(groupId: String, request: SendGroupMessageRequest): DeliveryResponse =
        authorized { api.sendGroupMessage(groupId, request, it) }

    suspend fun createMediaUpload(request: MediaUploadRequest): MediaUploadResponse = authorized { api.createMediaUpload(request, it) }

    suspend fun mediaStatus(mediaId: String): MediaStatusResponse = authorized { api.mediaStatus(mediaId, it) }

    suspend fun uploadMediaChunk(mediaId: String, offset: Long, bytes: ByteArray): MediaStatusResponse =
        authorized { api.uploadMediaChunk(mediaId, offset, bytes, it) }

    suspend fun downloadMediaChunk(mediaId: String, start: Long, endInclusive: Long): ByteArray =
        authorized { api.downloadMediaChunk(mediaId, start, endInclusive, it) }

    suspend fun deleteMedia(mediaId: String) = authorized { api.deleteMedia(mediaId, it) }

    suspend fun <T> withValidAccessToken(block: suspend (String) -> T): T = authorized(block)

    private suspend fun <T> authorized(block: suspend (String) -> T): T {
        val session = sessions.read() ?: throw ApiException("INVALID_CREDENTIALS", 401)
        return try {
            block(session.accessToken)
        } catch (error: ApiException) {
            if (error.httpStatus != 401) throw error
            block(refresh(session.accessToken).accessToken)
        }
    }

    private suspend fun refresh(failedAccessToken: String): TokenResponse = refreshMutex.withLock {
        val current = sessions.read() ?: throw ApiException("INVALID_CREDENTIALS", 401)
        if (current.accessToken != failedAccessToken) return@withLock current
        try {
            api.refresh(RefreshRequest(current.refreshToken)).also { sessions.save(it) }
        } catch (error: ApiException) {
            if (error.httpStatus == 401) sessions.clear()
            throw error
        }
    }
}
