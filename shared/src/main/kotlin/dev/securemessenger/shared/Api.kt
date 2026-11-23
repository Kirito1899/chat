package dev.securemessenger.shared

import kotlinx.serialization.Serializable

@Serializable
data class InviteResponse(val inviteCode: String)

@Serializable
data class RegisterRequest(
    val inviteCode: String,
    val email: String,
    val username: String,
    val password: String,
)

@Serializable
data class RegisterResponse(val accountId: String, val email: String)

@Serializable
data class EmailCodeRequest(val email: String, val code: String)

@Serializable
data class ResendVerificationRequest(val email: String)

@Serializable
data class LoginRequest(val email: String, val password: String, val deviceId: String)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAt: Long,
    val refreshExpiresAt: Long,
    val accountId: String,
    val deviceId: String,
    val username: String,
)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class LogoutRequest(val refreshToken: String)

@Serializable
data class RecoveryRequest(val email: String)

@Serializable
data class PasswordResetRequest(val email: String, val code: String, val newPassword: String)

@Serializable
data class UserResponse(val accountId: String, val username: String)

@Serializable
data class ErrorResponse(val code: String)

@Serializable
data class HealthResponse(val status: String)
