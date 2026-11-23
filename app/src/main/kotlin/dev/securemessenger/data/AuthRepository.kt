package dev.securemessenger.data

import dev.securemessenger.data.local.SessionStore
import dev.securemessenger.data.remote.ApiException
import dev.securemessenger.data.remote.MessengerApi
import dev.securemessenger.shared.DeliveredMessagesResponse
import dev.securemessenger.shared.DeviceKeysUpload
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

class AuthRepository(
    private val api: MessengerApi,
    private val sessions: SessionStore,
) : AccountRepository {
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
        val session = api.login(LoginRequest(email, password, sessions.deviceId()))
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
        val session = sessions.read() ?: throw ApiException("INVALID_CREDENTIALS", 401)
        return try {
            api.findByUsername(normalized, session.accessToken)
        } catch (error: ApiException) {
            if (error.httpStatus != 401) throw error
            val rotated = refresh()
            api.findByUsername(normalized, rotated.accessToken)
        }
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

    private suspend fun <T> authorized(block: suspend (String) -> T): T {
        val session = sessions.read() ?: throw ApiException("INVALID_CREDENTIALS", 401)
        return try {
            block(session.accessToken)
        } catch (error: ApiException) {
            if (error.httpStatus != 401) throw error
            block(refresh().accessToken)
        }
    }

    private suspend fun refresh(): TokenResponse {
        val current = sessions.read() ?: throw ApiException("INVALID_CREDENTIALS", 401)
        return try {
            api.refresh(RefreshRequest(current.refreshToken)).also { sessions.save(it) }
        } catch (error: ApiException) {
            if (error.httpStatus == 401) sessions.clear()
            throw error
        }
    }
}
