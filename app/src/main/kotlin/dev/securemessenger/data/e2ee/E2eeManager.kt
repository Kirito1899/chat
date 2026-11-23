package dev.securemessenger.data.e2ee

import dev.securemessenger.data.AuthRepository
import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.IncomingMessage
import dev.securemessenger.shared.PreKeyBundleResponse
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.UserResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.PreKeyBundle
import java.util.Base64
import java.util.UUID

class DuplicateEnvelopeException : RuntimeException()
class IdentityChangedException(val accountId: String) : RuntimeException()

data class RemoteSession(
    val accountId: String,
    val deviceId: String,
    val address: SignalProtocolAddress,
)

class E2eeManager(
    private val store: PersistentSignalStore,
    private val accounts: AuthRepository,
    private val preKeyFactory: PreKeyFactory,
    private val json: Json,
) {
    private val initialization = Mutex()
    private val crypto = Mutex()

    suspend fun initializeDevice() = initialization.withLock {
        if (store.localText("keys_uploaded") == "true") return@withLock
        val upload = store.localText("pending_upload")?.let {
            json.decodeFromString<DeviceKeysUpload>(it)
        } ?: preKeyFactory.generateInitial(store).also {
            store.putLocalText("pending_upload", json.encodeToString(DeviceKeysUpload.serializer(), it))
        }
        accounts.uploadKeys(upload)
        store.deleteLocalText("pending_upload")
        store.putLocalText("keys_uploaded", "true")
    }

    suspend fun encryptText(user: UserResponse, messageId: String, body: String): SendMessageRequest = crypto.withLock {
        val remote = ensureRemoteSession(user)
        val payload = ChatPayload(ChatPayload.TEXT, messageId, body, System.currentTimeMillis())
        encrypt(remote, user.accountId, payload, messageId)
    }

    suspend fun encryptReadReceipt(user: UserResponse, originalMessageId: String): SendMessageRequest = crypto.withLock {
        val remote = ensureRemoteSession(user)
        val messageId = UUID.randomUUID().toString()
        val payload = ChatPayload(ChatPayload.READ_RECEIPT, originalMessageId, "", System.currentTimeMillis())
        encrypt(remote, user.accountId, payload, messageId)
    }

    suspend fun decrypt(message: IncomingMessage): ChatPayload = withContext(Dispatchers.IO) {
        crypto.withLock {
            val remote = SignalProtocolAddress(protocolName(message.senderAccountId, message.senderDeviceId), 1)
            val bytes = Base64.getDecoder().decode(message.ciphertext)
            val plaintext = try {
                when (message.ciphertextType) {
                    3 -> SessionCipher(store, remote).decrypt(PreKeySignalMessage(bytes))
                    2 -> SessionCipher(store, remote).decrypt(SignalMessage(bytes))
                    else -> throw IllegalArgumentException("Unsupported ciphertext type")
                }
            } catch (_: DuplicateMessageException) {
                throw DuplicateEnvelopeException()
            } catch (_: UntrustedIdentityException) {
                throw IdentityChangedException(message.senderAccountId)
            }
            json.decodeFromString<ChatPayload>(plaintext.decodeToString())
        }
    }

    private fun encrypt(remote: RemoteSession, accountId: String, payload: ChatPayload, messageId: String): SendMessageRequest {
        val plaintext = json.encodeToString(ChatPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            SessionCipher(store, remote.address).encrypt(plaintext)
        } catch (_: UntrustedIdentityException) {
            throw IdentityChangedException(accountId)
        }
        return SendMessageRequest(
            messageId = messageId,
            recipientDeviceId = remote.deviceId,
            ciphertextType = ciphertext.type,
            ciphertext = ciphertext.serialize().base64(),
        )
    }

    private suspend fun ensureRemoteSession(user: UserResponse): RemoteSession {
        val savedDevice = store.localText("remote_device:${user.accountId}")
        if (savedDevice != null) {
            val address = SignalProtocolAddress(protocolName(user.accountId, savedDevice), 1)
            if (store.containsSession(address)) {
                return RemoteSession(user.accountId, savedDevice, address)
            }
        }
        val response = accounts.fetchPreKeyBundle(user.username)
        val address = SignalProtocolAddress(protocolName(response.accountId, response.deviceId), response.protocolDeviceId)
        SessionBuilder(store, address).process(response.toLibsignal())
        store.putLocalText("remote_device:${user.accountId}", response.deviceId)
        return RemoteSession(response.accountId, response.deviceId, address)
    }
}

fun PreKeyBundleResponse.toLibsignal(): PreKeyBundle = PreKeyBundle(
    registrationId,
    protocolDeviceId,
    preKey?.id ?: PreKeyBundle.NULL_PRE_KEY_ID,
    preKey?.publicKey?.let { ECPublicKey(Base64.getDecoder().decode(it)) },
    signedPreKey.id,
    ECPublicKey(Base64.getDecoder().decode(signedPreKey.publicKey)),
    Base64.getDecoder().decode(signedPreKey.signature),
    IdentityKey(Base64.getDecoder().decode(identityKey)),
    kyberPreKey.id,
    KEMPublicKey(Base64.getDecoder().decode(kyberPreKey.publicKey)),
    Base64.getDecoder().decode(kyberPreKey.signature),
)

fun protocolName(accountId: String, deviceId: String): String = "$accountId:$deviceId"
