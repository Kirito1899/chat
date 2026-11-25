package dev.securemessenger.data.e2ee

import dev.securemessenger.data.AuthRepository
import dev.securemessenger.data.remote.ApiException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.IncomingMessage
import dev.securemessenger.shared.PreKeyBundleResponse
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.UserResponse
import dev.securemessenger.shared.TokenResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.DuplicateMessageException
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.UntrustedIdentityException
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.kem.KEMPublicKey
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import java.util.Base64
import java.util.UUID

class DuplicateEnvelopeException : RuntimeException()
class IdentityChangedException(val accountId: String) : RuntimeException()

data class RemoteSession(
    val accountId: String,
    val deviceId: String,
    val address: SignalProtocolAddress,
)

internal interface E2eeStore : SignalProtocolStore {
    fun localText(key: String): String?
    fun putLocalText(key: String, value: String)
    fun deleteLocalText(key: String)
    fun acceptIdentitySession(
        address: SignalProtocolAddress,
        identity: IdentityKey,
        session: SessionRecord,
        accountId: String,
        deviceId: String,
    )
}

internal interface E2eeAccounts {
    suspend fun restore(): TokenResponse?
    suspend fun uploadKeys(request: DeviceKeysUpload)
    suspend fun fetchPreKeyBundle(username: String): PreKeyBundleResponse
}

class E2eeManager internal constructor(
    private val store: E2eeStore,
    private val accounts: E2eeAccounts,
    private val generateInitial: () -> DeviceKeysUpload,
    private val json: Json,
    private val safetyNumbers: SafetyNumberService = SafetyNumberService(),
    private val generateRotation: () -> DeviceKeysUpload = generateInitial,
) {
    constructor(
        store: PersistentSignalStore,
        accounts: AuthRepository,
        preKeyFactory: PreKeyFactory,
        json: Json,
    ) : this(
        PersistentE2eeStore(store),
        RepositoryE2eeAccounts(accounts),
        { preKeyFactory.generateInitial(store) },
        json,
    )

    private val initialization = Mutex()
    private val crypto = Mutex()
    private val changedIdentities = mutableSetOf<String>()
    private val pendingIdentityBundles = mutableMapOf<String, PreKeyBundleResponse>()

    suspend fun initializeDevice() = withContext(Dispatchers.IO) {
        initialization.withLock {
            if (store.localText("keys_uploaded") == "true") {
                if (needsPreKeyCheck()) {
                    try {
                        replenishPreKeysIfNeeded()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: IOException) {
                        store.putLocalText("prekey_refresh_pending", "true")
                        throw error
                    } catch (error: ApiException) {
                        if (error.isTransientPreKeyError()) store.putLocalText("prekey_refresh_pending", "true")
                        throw error
                    }
                }
                return@withLock
            }
            val upload = store.localText("pending_upload")?.let {
                json.decodeFromString<DeviceKeysUpload>(it)
            } ?: generateInitial().also {
                store.putLocalText("pending_upload", json.encodeToString(DeviceKeysUpload.serializer(), it))
            }
            accounts.uploadKeys(upload)
            store.deleteLocalText("pending_upload")
            store.putLocalText("keys_uploaded", "true")
            store.putLocalText("last_prekey_check", System.currentTimeMillis().toString())
        }
    }

    suspend fun encryptText(user: UserResponse, messageId: String, body: String): SendMessageRequest = withContext(Dispatchers.IO) {
        crypto.withLock {
            val remote = ensureRemoteSession(user)
            val payload = ChatPayload(ChatPayload.TEXT, messageId, body, System.currentTimeMillis())
            encrypt(remote, user.accountId, payload, messageId)
        }
    }

    suspend fun encryptReadReceipt(user: UserResponse, originalMessageId: String): SendMessageRequest = withContext(Dispatchers.IO) {
        crypto.withLock {
            val remote = ensureRemoteSession(user)
            val messageId = UUID.randomUUID().toString()
            val payload = ChatPayload(ChatPayload.READ_RECEIPT, originalMessageId, "", System.currentTimeMillis())
            encrypt(remote, user.accountId, payload, messageId)
        }
    }

    suspend fun encryptDirectPayload(user: UserResponse, payload: ChatPayload): SendMessageRequest = withContext(Dispatchers.IO) {
        crypto.withLock {
            val remote = ensureRemoteSession(user)
            encrypt(remote, user.accountId, payload, payload.messageId)
        }
    }

    suspend fun encryptPayload(user: UserResponse, payload: ChatPayload): SendMessageRequest = withContext(Dispatchers.IO) {
        crypto.withLock {
            val remote = ensureRemoteSession(user)
            encrypt(remote, user.accountId, payload, UUID.randomUUID().toString())
        }
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
                throw identityChanged(message.senderAccountId)
            }
            val payload = json.decodeFromString<ChatPayload>(plaintext.decodeToString())
            if (payload.groupId != message.groupId) throw InvalidMessageException("Group context mismatch")
            if (payload.kind !in ChatPayload.receipts && message.groupId != null && payload.messageId != message.groupMessageId) {
                throw InvalidMessageException("Group message ID mismatch")
            }
            if (payload.kind !in ChatPayload.receipts && message.groupId == null && payload.messageId != message.messageId) {
                throw InvalidMessageException("Direct message ID mismatch")
            }
            payload
        }
    }

    suspend fun safetyNumber(user: UserResponse): SafetyNumber = withContext(Dispatchers.IO) {
        crypto.withLock {
            val local = accounts.restore() ?: error("No local session")
            val replacement = reviewedBundle(user)
            val deviceId: String
            val remoteIdentity: IdentityKey
            if (replacement != null) {
                deviceId = replacement.deviceId
                remoteIdentity = replacement.toLibsignal().verifySignatures().identityKey
            } else {
                deviceId = store.localText("remote_device:${user.accountId}") ?: error("No remote session")
                val address = SignalProtocolAddress(protocolName(user.accountId, deviceId), 1)
                remoteIdentity = store.getIdentity(address) ?: error("No remote identity")
            }
            safetyNumbers.create(
                SafetyNumberParty(local.accountId, local.deviceId, store.identityKeyPair.publicKey),
                SafetyNumberParty(user.accountId, deviceId, remoteIdentity),
            )
        }
    }

    suspend fun acceptChangedIdentity(user: UserResponse) = withContext(Dispatchers.IO) {
        crypto.withLock {
            if (user.accountId !in changedIdentities) return@withLock
            val response = reviewedBundle(user) ?: return@withLock
            val address = SignalProtocolAddress(protocolName(response.accountId, response.deviceId), response.protocolDeviceId)
            val bundle = response.toLibsignal().verifySignatures()
            val targetAddress = address
            var acceptedSession: SessionRecord? = null
            val acceptingStore = object : E2eeStore, SignalProtocolStore by store {
                override fun localText(key: String): String? = store.localText(key)
                override fun putLocalText(key: String, value: String) = store.putLocalText(key, value)
                override fun deleteLocalText(key: String) = store.deleteLocalText(key)

                override fun acceptIdentitySession(
                    address: SignalProtocolAddress,
                    identity: IdentityKey,
                    session: SessionRecord,
                    accountId: String,
                    deviceId: String,
                ) = store.acceptIdentitySession(address, identity, session, accountId, deviceId)

                override fun isTrustedIdentity(
                    address: SignalProtocolAddress,
                    identityKey: IdentityKey,
                    direction: IdentityKeyStore.Direction,
                ): Boolean = if (address == targetAddress && identityKey == bundle.identityKey) {
                    true
                } else {
                    store.isTrustedIdentity(address, identityKey, direction)
                }

                override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
                    if (address != targetAddress || identityKey != bundle.identityKey) {
                        return store.saveIdentity(address, identityKey)
                    }
                    return if (store.getIdentity(address) == null) {
                        IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
                    } else {
                        IdentityKeyStore.IdentityChange.REPLACED_EXISTING
                    }
                }

                override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
                    if (address == targetAddress) acceptedSession = record
                    else store.storeSession(address, record)
                }

            }
            SessionBuilder(acceptingStore, address).process(bundle)
            store.acceptIdentitySession(
                address,
                bundle.identityKey,
                acceptedSession ?: error("Session builder did not persist a session"),
                response.accountId,
                response.deviceId,
            )
            pendingIdentityBundles.remove(user.accountId)
            changedIdentities.remove(user.accountId)
        }
    }

    private fun encrypt(remote: RemoteSession, accountId: String, payload: ChatPayload, messageId: String): SendMessageRequest {
        val plaintext = json.encodeToString(ChatPayload.serializer(), payload).toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            SessionCipher(store, remote.address).encrypt(plaintext)
        } catch (_: UntrustedIdentityException) {
            throw identityChanged(accountId)
        }
        return SendMessageRequest(
            messageId = messageId,
            recipientDeviceId = remote.deviceId,
            ciphertextType = ciphertext.type,
            ciphertext = ciphertext.serialize().base64(),
            mediaId = payload.media?.mediaId,
        )
    }

    private suspend fun reviewedBundle(user: UserResponse): PreKeyBundleResponse? {
        if (user.accountId !in changedIdentities) return null
        pendingIdentityBundles[user.accountId]?.let { return it }
        val response = accounts.fetchPreKeyBundle(user.username)
        if (response.accountId != user.accountId) throw IdentityChangedException(user.accountId)
        response.toLibsignal().verifySignatures()
        pendingIdentityBundles[user.accountId] = response
        return response
    }

    private fun identityChanged(accountId: String): IdentityChangedException {
        changedIdentities += accountId
        return IdentityChangedException(accountId)
    }

    private suspend fun ensureRemoteSession(user: UserResponse): RemoteSession {
        val savedDevice = store.localText("remote_device:${user.accountId}")
        if (savedDevice != null) {
            val address = SignalProtocolAddress(protocolName(user.accountId, savedDevice), 1)
            if (store.containsSession(address)) {
                refreshPreKeysAfterSession()
                return RemoteSession(user.accountId, savedDevice, address)
            }
        }
        val response = accounts.fetchPreKeyBundle(user.username)
        if (response.accountId != user.accountId) throw IdentityChangedException(user.accountId)
        val address = SignalProtocolAddress(protocolName(response.accountId, response.deviceId), response.protocolDeviceId)
        try {
            SessionBuilder(store, address).process(response.toLibsignal().verifySignatures())
        } catch (_: UntrustedIdentityException) {
            throw identityChanged(user.accountId)
        }
        store.putLocalText("remote_device:${user.accountId}", response.deviceId)
        refreshPreKeysAfterSession()
        return RemoteSession(response.accountId, response.deviceId, address)
    }

    private suspend fun refreshPreKeysAfterSession() {
        if (!needsPreKeyCheck()) return
        try {
            replenishPreKeysIfNeeded()
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            store.putLocalText("prekey_refresh_pending", "true")
        } catch (error: ApiException) {
            if (error.isTransientPreKeyError()) store.putLocalText("prekey_refresh_pending", "true")
            else throw error
        }
    }

    private fun ApiException.isTransientPreKeyError(): Boolean = httpStatus == 401 || httpStatus == 429 || httpStatus >= 500

    private fun needsPreKeyCheck(): Boolean {
        if (store.localText("prekey_refresh_pending") == "true") return true
        val elapsed = System.currentTimeMillis() - (store.localText("last_prekey_check")?.toLongOrNull() ?: 0L)
        return elapsed < 0 || elapsed >= PREKEY_CHECK_INTERVAL_MILLIS
    }

    private suspend fun replenishPreKeysIfNeeded() {
        val session = accounts.restore() ?: error("No local session")
        val response = accounts.fetchPreKeyBundle(session.username)
        if (response.accountId != session.accountId || response.deviceId != session.deviceId ||
            response.registrationId != store.localRegistrationId
        ) {
            throw IdentityChangedException(session.accountId)
        }
        val verified = response.toLibsignal().verifySignatures()
        if (!verified.identityKey.publicKey.serialize().contentEquals(store.identityKeyPair.publicKey.serialize())) {
            throw IdentityChangedException(session.accountId)
        }
        if (response.remainingPreKeys < MIN_REMAINING_PREKEYS) {
            val fresh = generateRotation()
            val rotation = DeviceKeysUpload(
                registrationId = response.registrationId,
                identityKey = response.identityKey,
                signedPreKey = response.signedPreKey,
                preKeys = fresh.preKeys,
                kyberPreKeys = fresh.kyberPreKeys,
                lastResortKyberPreKey = response.kyberPreKey,
            )
            rotation.validate()
            accounts.uploadKeys(rotation)
        }
        store.putLocalText("last_prekey_check", System.currentTimeMillis().toString())
        store.deleteLocalText("prekey_refresh_pending")
    }
}

private const val PREKEY_CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1000L
private const val MIN_REMAINING_PREKEYS = 20

private class PersistentE2eeStore(
    private val persistent: PersistentSignalStore,
) : E2eeStore, SignalProtocolStore by persistent {
    override fun localText(key: String): String? = persistent.localText(key)

    override fun putLocalText(key: String, value: String) = persistent.putLocalText(key, value)

    override fun deleteLocalText(key: String) = persistent.deleteLocalText(key)

    override fun acceptIdentitySession(
        address: SignalProtocolAddress,
        identity: IdentityKey,
        session: SessionRecord,
        accountId: String,
        deviceId: String,
    ) = persistent.acceptIdentitySession(address, identity, session, accountId, deviceId)
}

private class RepositoryE2eeAccounts(
    private val repository: AuthRepository,
) : E2eeAccounts {
    override suspend fun restore(): TokenResponse? = repository.restore()

    override suspend fun uploadKeys(request: DeviceKeysUpload) = repository.uploadKeys(request)

    override suspend fun fetchPreKeyBundle(username: String): PreKeyBundleResponse = repository.fetchPreKeyBundle(username)
}

private fun PreKeyBundle.verifySignatures(): PreKeyBundle {
    val identity = identityKey.publicKey
    if (!identity.verifySignature(signedPreKey.serialize(), signedPreKeySignature) ||
        !identity.verifySignature(kyberPreKey.serialize(), kyberPreKeySignature)
    ) {
        throw InvalidKeyException("Invalid prekey bundle signature")
    }
    return this
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
