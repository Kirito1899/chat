package dev.securemessenger.data.remote

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

    suspend fun uploadKeys(request: DeviceKeysUpload, accessToken: String) {
        execute(secureRequest("PUT", "/v1/devices/keys", request, accessToken))
    }

    suspend fun fetchPreKeyBundle(username: String, accessToken: String): PreKeyBundleResponse =
        decode(execute(secureGet("/v1/prekey-bundles/by-username/$username", accessToken)))

    suspend fun sendMessage(request: SendMessageRequest, accessToken: String): DeliveryResponse =
        decode(execute(secureRequest("POST", "/v1/messages", request, accessToken)))

    suspend fun fetchMessages(accessToken: String): MessageListResponse =
        decode(execute(secureGet("/v1/messages", accessToken)))

    suspend fun markDelivered(messageId: String, accessToken: String) {
        execute(
            Request.Builder()
                .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/messages/$messageId/delivered").build())
                .header("Authorization", "Bearer $accessToken")
                .post("{}".toRequestBody("application/json".toMediaType()))
                .build(),
        )
    }

    suspend fun fetchDelivered(accessToken: String): DeliveredMessagesResponse =
        decode(execute(secureGet("/v1/messages/delivered", accessToken)))

    suspend fun createGroup(request: CreateGroupRequest, accessToken: String): GroupResponse =
        decode(execute(secureRequest("POST", "/v1/groups", request, accessToken)))

    suspend fun listGroups(accessToken: String): GroupListResponse =
        decode(execute(secureGet("/v1/groups", accessToken)))

    suspend fun getGroup(groupId: String, accessToken: String): GroupResponse =
        decode(execute(secureGet("/v1/groups/$groupId", accessToken)))

    suspend fun addGroupMembers(groupId: String, request: AddGroupMembersRequest, accessToken: String): GroupResponse =
        decode(execute(secureRequest("POST", "/v1/groups/$groupId/members", request, accessToken)))

    suspend fun removeGroupMember(groupId: String, accountId: String, accessToken: String): GroupResponse {
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/groups/$groupId/members/$accountId").build())
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        return decode(execute(request))
    }

    suspend fun sendGroupMessage(groupId: String, request: SendGroupMessageRequest, accessToken: String): DeliveryResponse =
        decode(execute(secureRequest("POST", "/v1/groups/$groupId/messages", request, accessToken)))

    suspend fun createMediaUpload(request: MediaUploadRequest, accessToken: String): MediaUploadResponse =
        decode(execute(secureRequest("POST", "/v1/media/uploads", request, accessToken)))

    suspend fun mediaStatus(mediaId: String, accessToken: String): MediaStatusResponse =
        decode(execute(secureGet("/v1/media/$mediaId/status", accessToken)))

    suspend fun uploadMediaChunk(mediaId: String, offset: Long, bytes: ByteArray, accessToken: String): MediaStatusResponse {
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/media/$mediaId/chunks/$offset").build())
            .header("Authorization", "Bearer $accessToken")
            .put(bytes.toRequestBody("application/octet-stream".toMediaType()))
            .build()
        return decode(execute(request))
    }

    suspend fun downloadMediaChunk(mediaId: String, start: Long, endInclusive: Long, accessToken: String): ByteArray {
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/media/$mediaId/content").build())
            .header("Authorization", "Bearer $accessToken")
            .header("Range", "bytes=$start-$endInclusive")
            .get()
            .build()
        return executeBytes(request)
    }

    suspend fun deleteMedia(mediaId: String, accessToken: String) {
        val request = Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments("v1/media/$mediaId").build())
            .header("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        execute(request)
    }

    private suspend fun execute(request: Request): String = executeBytes(request).decodeToString()

    private suspend fun executeBytes(request: Request): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            val body = response.body.bytes()
            if (!response.isSuccessful) {
                val code = runCatching { json.decodeFromString<ErrorResponse>(body.decodeToString()).code }
                    .getOrDefault("HTTP_${response.code}")
                throw ApiException(code, response.code)
            }
            body
        }
    }

    private inline fun <reified T> secureRequest(method: String, path: String, value: T, accessToken: String): Request {
        val body = json.encodeToString(value).toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).build())
            .header("Authorization", "Bearer $accessToken")
            .method(method, body)
            .build()
    }

    private fun secureGet(path: String, accessToken: String): Request = Request.Builder()
        .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).build())
        .header("Authorization", "Bearer $accessToken")
        .get()
        .build()

    private inline fun <reified T> post(path: String, value: T): Request {
        val body = json.encodeToString(value).toRequestBody("application/json".toMediaType())
        return Request.Builder()
            .url(baseUrl.toHttpUrl().newBuilder().addPathSegments(path.trimStart('/')).build())
            .post(body)
            .build()
    }

    private inline fun <reified T> decode(body: String): T = json.decodeFromString(body)
}
