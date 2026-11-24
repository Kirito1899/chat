package dev.securemessenger.shared

import kotlinx.serialization.Serializable
import java.util.Base64
import java.util.UUID

@Serializable
data class PreKeyDto(val id: Int, val publicKey: String)

@Serializable
data class SignedPreKeyDto(val id: Int, val publicKey: String, val signature: String)

@Serializable
data class KyberPreKeyDto(val id: Int, val publicKey: String, val signature: String)

@Serializable
data class DeviceKeysUpload(
    val registrationId: Int,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val preKeys: List<PreKeyDto>,
    val kyberPreKeys: List<KyberPreKeyDto>,
    val lastResortKyberPreKey: KyberPreKeyDto,
) {
    fun validate() {
        requireValid(registrationId in 1..16380, "INVALID_REGISTRATION_ID")
        identityKey.decodeKey(33)
        signedPreKey.validate()
        requireValid(preKeys.size in 1..100, "INVALID_PREKEY_COUNT")
        requireValid(kyberPreKeys.size in 1..100, "INVALID_PREKEY_COUNT")
        requireValid(preKeys.map { it.id }.toSet().size == preKeys.size, "DUPLICATE_PREKEY_ID")
        requireValid(kyberPreKeys.map { it.id }.toSet().size == kyberPreKeys.size, "DUPLICATE_PREKEY_ID")
        preKeys.forEach { it.validate() }
        kyberPreKeys.forEach { it.validate() }
        lastResortKyberPreKey.validate()
        requireValid(lastResortKyberPreKey.id !in kyberPreKeys.map { it.id }, "DUPLICATE_PREKEY_ID")
    }
}

@Serializable
data class PreKeyBundleResponse(
    val accountId: String,
    val deviceId: String,
    val registrationId: Int,
    val protocolDeviceId: Int = 1,
    val identityKey: String,
    val signedPreKey: SignedPreKeyDto,
    val preKey: PreKeyDto?,
    val kyberPreKey: KyberPreKeyDto,
    val remainingPreKeys: Int,
)

@Serializable
data class SendMessageRequest(
    val messageId: String,
    val recipientDeviceId: String,
    val ciphertextType: Int,
    val ciphertext: String,
) {
    fun validate() {
        messageId.requireUuid()
        recipientDeviceId.requireUuid()
        requireValid(ciphertextType == 2 || ciphertextType == 3, "INVALID_CIPHERTEXT_TYPE")
        val bytes = ciphertext.decodeKey(null)
        requireValid(bytes.isNotEmpty() && bytes.size <= 32 * 1024, "INVALID_CIPHERTEXT_SIZE")
    }
}

@Serializable
data class IncomingMessage(
    val messageId: String,
    val senderAccountId: String,
    val senderDeviceId: String,
    val ciphertextType: Int,
    val ciphertext: String,
    val createdAt: Long,
)

@Serializable
data class MessageListResponse(val messages: List<IncomingMessage>)

@Serializable
data class DeliveryResponse(val messageId: String, val delivered: Boolean)

@Serializable
data class DeliveredMessagesResponse(val messageIds: List<String>)

private fun PreKeyDto.validate() {
    requireValid(id in 1..0xFFFFFF, "INVALID_PREKEY_ID")
    publicKey.decodeKey(33)
}

private fun SignedPreKeyDto.validate() {
    requireValid(id in 1..0xFFFFFF, "INVALID_PREKEY_ID")
    publicKey.decodeKey(33)
    signature.decodeKey(64)
}

private fun KyberPreKeyDto.validate() {
    requireValid(id in 1..0xFFFFFF, "INVALID_PREKEY_ID")
    publicKey.decodeKey(SERIALIZED_KYBER_1024_PUBLIC_KEY_SIZE)
    signature.decodeKey(64)
}

private const val SERIALIZED_KYBER_1024_PUBLIC_KEY_SIZE = 1569

private fun requireValid(condition: Boolean, code: String) {
    if (!condition) throw ValidationException(code)
}

private fun String.decodeKey(expectedSize: Int?): ByteArray {
    val bytes = try {
        Base64.getDecoder().decode(this)
    } catch (_: IllegalArgumentException) {
        throw ValidationException("INVALID_BASE64")
    }
    if (expectedSize != null && bytes.size != expectedSize) {
        throw ValidationException("INVALID_KEY_SIZE")
    }
    return bytes
}

private fun String.requireUuid() {
    runCatching { UUID.fromString(this) }.getOrNull() ?: throw ValidationException("INVALID_UUID")
}
