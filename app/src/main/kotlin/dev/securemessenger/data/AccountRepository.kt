package dev.securemessenger.data

import dev.securemessenger.shared.RegisterResponse
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse

interface AccountRepository {
    suspend fun restore(): TokenResponse?
    suspend fun register(invite: String, email: String, username: String, password: String): RegisterResponse
    suspend fun verifyEmail(email: String, code: String)
    suspend fun resendVerification(email: String)
    suspend fun login(email: String, password: String): TokenResponse
    suspend fun recover(email: String)
    suspend fun resetPassword(email: String, code: String, newPassword: String)
    suspend fun searchByUsername(username: String): UserResponse
    suspend fun logout()
}
