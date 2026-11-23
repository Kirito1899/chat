package dev.securemessenger.data.remote

import dev.securemessenger.shared.EmailCodeRequest
import dev.securemessenger.shared.ErrorResponse
import dev.securemessenger.shared.LoginRequest
import dev.securemessenger.shared.LogoutRequest
import dev.securemessenger.shared.PasswordResetRequest
import dev.securemessenger.shared.RecoveryRequest
import dev.securemessenger.shared.RefreshRequest
import dev.securemessenger.shared.RegisterRequest
import dev.securemessenger.shared.RegisterResponse
import dev.securemessenger.shared.ResendVerificationRequest
import dev.securemessenger.shared.TokenResponse
import dev.securemessenger.shared.UserResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class ApiException(val code: String, val httpStatus: Int) : Exception(code)

class MessengerApi(
    private val baseUrl: String,
    private val client: OkHttpClient,
    private val json: Json = Json,
) {
    suspend fun register(request: RegisterRequest): RegisterResponse =
        decode(execute(post("/v1/auth/register", request)))

    suspend fun verifyEmail(request: EmailCodeRequest) {
        execute(post("/v1/auth/verify-email", request))
    }

    suspend fun resendVerification(email: String) {
        execute(post("/v1/auth/resend-verification", ResendVerificationRequest(email)))
    }

    suspend fun login(request: LoginRequest): TokenResponse =
        decode(execute(post("/v1/auth/login", request)))

    suspend fun refresh(request: RefreshRequest): TokenResponse =
        decode(execute(post("/v1/auth/refresh", request)))

    suspend fun recover(email: String) {
        execute(post("/v1/auth/recovery", RecoveryRequest(email)))
    }

    suspend fun resetPassword(request: PasswordResetRequest) {
        execute(post("/v1/auth/reset", request))
    }

    suspend fun logout(request: LogoutRequest) {
        execute(post("/v1/auth/logout", request))
    }

    suspend fun findByUsername(username: String, accessToken: String): UserResponse {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("v1/users/by-username")
            .addPathSegment(username)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()
        return decode(execute(request))
    }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body.string()
            if (!response.isSuccessful) {
                val code = runCatching { json.decodeFromString<ErrorResponse>(body).code }
                    .getOrDefault("HTTP_${response.code}")
                throw ApiException(code, response.code)
            }
            body
        }
    }

    private inline fun <reified T> post(path: String, value: T): Request {
        val body = json.encodeToString(value).toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).build())
            .post(body)
            .build()
    }

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)
}
