package dev.securemessenger.data.e2ee

import dev.securemessenger.shared.DeviceKeysUpload
import dev.securemessenger.shared.IncomingMessage
import dev.securemessenger.shared.KyberPreKeyDto
import dev.securemessenger.shared.PreKeyBundleResponse
import dev.securemessenger.shared.PreKeyDto
import dev.securemessenger.shared.SendMessageRequest
import dev.securemessenger.shared.SignedPreKeyDto
import dev.securemessenger.shared.UserResponse
import dev.securemessenger.shared.TokenResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyException
import org.signal.libsignal.protocol.InvalidMessageException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

class E2eeManagerTest {
    private val json = Json

    @Test
    fun `generated libsignal keys satisfy the upload contract`() {
        val upload = generateLocalKeys(TestStore())

        assertEquals(33, decode(upload.identityKey).size, "identity key")
        assertEquals(33, decode(upload.signedPreKey.publicKey).size, "signed prekey")
        assertEquals(64, decode(upload.signedPreKey.signature).size, "signed prekey signature")
        assertEquals(33, decode(upload.preKeys.single().publicKey).size, "one-time prekey")
        assertEquals(1569, decode(upload.kyberPreKeys.single().publicKey).size, "Kyber prekey")
        assertEquals(64, decode(upload.kyberPreKeys.single().signature).size, "Kyber signature")
        assertEquals(1569, decode(upload.lastResortKyberPreKey.publicKey).size, "last-resort Kyber prekey")
        upload.validate()
    }

    @Test
    fun `bundle conversion preserves all PQXDH fields`() {
        val store = TestStore()
        val upload = generateLocalKeys(store)
        val response = upload.toBundle(RECIPIENT_ACCOUNT, RECIPIENT_DEVICE)

        val bundle = response.toLibsignal()

        assertEquals(response.registrationId, bundle.registrationId)
        assertEquals(response.protocolDeviceId, bundle.deviceId)
        val preKeyDto = requireNotNull(response.preKey)
        val preKey = requireNotNull(bundle.preKey)
        assertEquals(preKeyDto.id, bundle.preKeyId)
        assertContentEquals(decode(preKeyDto.publicKey), preKey.serialize())
        assertEquals(response.signedPreKey.id, bundle.signedPreKeyId)
        assertContentEquals(decode(response.signedPreKey.publicKey), bundle.signedPreKey.serialize())
        assertContentEquals(decode(response.signedPreKey.signature), bundle.signedPreKeySignature)
        assertEquals(IdentityKey(decode(response.identityKey)), bundle.identityKey)
        assertEquals(response.kyberPreKey.id, bundle.kyberPreKeyId)
        assertContentEquals(decode(response.kyberPreKey.publicKey), bundle.kyberPreKey.serialize())
        assertContentEquals(decode(response.kyberPreKey.signature), bundle.kyberPreKeySignature)
    }

    @Test
    fun `bundle conversion supports last resort Kyber key without EC prekey`() {
        val store = TestStore()
        val upload = generateLocalKeys(store)
        val response = upload.toBundle(
            RECIPIENT_ACCOUNT,
            RECIPIENT_DEVICE,
            includePreKey = false,
            useLastResortKyber = true,
        )

        val bundle = response.toLibsignal()

        assertEquals(PreKeyBundle.NULL_PRE_KEY_ID, bundle.preKeyId)
        assertNull(bundle.preKey)
        assertEquals(upload.lastResortKyberPreKey.id, bundle.kyberPreKeyId)
        assertContentEquals(
            decode(upload.lastResortKyberPreKey.publicKey),
            bundle.kyberPreKey.serialize(),
        )
    }

    @Test
    fun `failed upload retries pending keys without regenerating`() = runTest {
        val store = TestStore()
        val upload = generateLocalKeys(TestStore())
        val accounts = FakeAccounts().apply { failUploads = 1 }
        var generations = 0
        val manager = E2eeManager(store, accounts, {
            generations++
            upload
        }, json)

        assertFailsWith<IOException> { manager.initializeDevice() }
        assertEquals(1, generations)
        assertTrue(store.localText("pending_upload") != null)
        assertNull(store.localText("keys_uploaded"))

        manager.initializeDevice()
        manager.initializeDevice()

        assertEquals(1, generations)
        assertEquals(2, accounts.uploadAttempts)
        assertEquals(listOf(upload, upload), accounts.uploadRequests)
        assertNull(store.localText("pending_upload"))
        assertEquals("true", store.localText("keys_uploaded"))
    }

    @Test
    fun `concurrent initialization generates and uploads keys once`() = runTest {
        val store = TestStore()
        val upload = generateLocalKeys(TestStore())
        val accounts = FakeAccounts().apply {
            uploadStarted = CompletableDeferred()
            continueUpload = CompletableDeferred()
        }
        var generations = 0
        val manager = E2eeManager(store, accounts, {
            generations++
            upload
        }, json)

        val first = launch { manager.initializeDevice() }
        accounts.uploadStarted!!.await()
        val second = launch { manager.initializeDevice() }
        yield()
        assertEquals(1, accounts.uploadAttempts)

        accounts.continueUpload!!.complete(Unit)
        joinAll(first, second)

        assertEquals(1, generations)
        assertEquals(1, accounts.uploadAttempts)
        assertEquals("true", store.localText("keys_uploaded"))
    }

    @Test
    fun `initialization does not block the caller dispatcher`() = runTest {
        val caller = Thread.currentThread()
        val store = TestStore()
        val upload = generateLocalKeys(TestStore())
        val accounts = FakeAccounts()
        val manager = E2eeManager(store, accounts, { upload }, json)

        manager.initializeDevice()

        assertNotSame(caller, accounts.uploadThread)
    }

    @Test
    fun `session establishment does not block the caller dispatcher`() = runTest {
        val caller = Thread.currentThread()
        val fixture = fixture()

        fixture.sender.encryptText(fixture.recipientUser, messageId(), "dispatcher-test")

        assertNotSame(caller, fixture.accounts.fetchThread)
    }

    @Test
    fun `initial prekey and subsequent whisper messages round trip`() = runTest {
        val fixture = fixture()
        val firstId = messageId()

        val first = fixture.sender.encryptText(fixture.recipientUser, firstId, "first-body")
        val firstPayload = fixture.recipient.decrypt(first.incoming(SENDER_ACCOUNT, SENDER_DEVICE))

        assertEquals(3, first.ciphertextType)
        assertEquals(ChatPayload(ChatPayload.TEXT, firstId, "first-body", firstPayload.createdAt), firstPayload)
        assertEquals(
            IdentityKey(decode(fixture.recipientUpload.identityKey)),
            fixture.senderStore.getIdentity(fixture.recipientAddress),
        )

        val reply = fixture.recipient.encryptText(fixture.senderUser, messageId(), "reply-body")
        val replyPayload = fixture.sender.decrypt(reply.incoming(RECIPIENT_ACCOUNT, RECIPIENT_DEVICE))
        assertEquals("reply-body", replyPayload.body)

        val secondId = messageId()
        val second = fixture.sender.encryptText(fixture.recipientUser, secondId, "second-body")
        val secondPayload = fixture.recipient.decrypt(second.incoming(SENDER_ACCOUNT, SENDER_DEVICE))

        assertEquals(2, second.ciphertextType)
        assertEquals(secondId, secondPayload.messageId)
        assertEquals("second-body", secondPayload.body)
    }

    @Test
    fun `group payloads use separate envelope ids and preserve group context`() = runTest {
        val fixture = fixture()
        establishSessions(fixture)
        val groupId = UUID.randomUUID().toString()
        val payload = ChatPayload(ChatPayload.GROUP_TEXT, messageId(), "group-body", 123, groupId)

        val request = fixture.sender.encryptPayload(fixture.recipientUser, payload)
        val second = fixture.sender.encryptPayload(fixture.recipientUser, payload)

        assertNotEquals(payload.messageId, request.messageId)
        assertNotEquals(request.messageId, second.messageId)
        assertNotEquals(request.ciphertext, second.ciphertext)
        val incoming = request.incoming(SENDER_ACCOUNT, SENDER_DEVICE).copy(
            groupId = groupId,
            groupMessageId = payload.messageId,
        )
        assertEquals(payload, fixture.recipient.decrypt(incoming))
        assertFailsWith<InvalidMessageException> {
            fixture.recipient.decrypt(
                second.incoming(SENDER_ACCOUNT, SENDER_DEVICE).copy(
                    groupId = UUID.randomUUID().toString(),
                    groupMessageId = payload.messageId,
                ),
            )
        }
    }

    @Test
    fun `direct envelope and payload ids must match`() = runTest {
        val fixture = fixture()
        val request = fixture.sender.encryptText(fixture.recipientUser, messageId(), "body")

        assertFailsWith<InvalidMessageException> {
            fixture.recipient.decrypt(request.incoming(SENDER_ACCOUNT, SENDER_DEVICE).copy(messageId = messageId()))
        }
    }

    @Test
    fun `out of order whisper messages decrypt`() = runTest {
        val fixture = fixture()
        establishSessions(fixture)
        val earlier = fixture.sender.encryptText(fixture.recipientUser, messageId(), "earlier")
        val later = fixture.sender.encryptText(fixture.recipientUser, messageId(), "later")

        val laterPayload = fixture.recipient.decrypt(later.incoming(SENDER_ACCOUNT, SENDER_DEVICE))
        val earlierPayload = fixture.recipient.decrypt(earlier.incoming(SENDER_ACCOUNT, SENDER_DEVICE))

        assertEquals("later", laterPayload.body)
        assertEquals("earlier", earlierPayload.body)
    }

    @Test
    fun `duplicate envelope is rejected`() = runTest {
        val fixture = fixture()
        val request = fixture.sender.encryptText(fixture.recipientUser, messageId(), "once")
        val incoming = request.incoming(SENDER_ACCOUNT, SENDER_DEVICE)
        fixture.recipient.decrypt(incoming)

        assertFailsWith<DuplicateEnvelopeException> { fixture.recipient.decrypt(incoming) }
    }

    @Test
    fun `tampered whisper envelope is rejected`() = runTest {
        val fixture = fixture()
        establishSessions(fixture)
        val request = fixture.sender.encryptText(fixture.recipientUser, messageId(), "authentic")
        val tampered = decode(request.ciphertext).also {
            val index = it.size / 2
            it[index] = (it[index].toInt() xor 1).toByte()
        }
        val incoming = request.copy(ciphertext = tampered.base64()).incoming(SENDER_ACCOUNT, SENDER_DEVICE)

        assertFailsWith<InvalidMessageException> { fixture.recipient.decrypt(incoming) }
    }

    @Test
    fun `identity replacement blocks send and preserves trusted identity`() = runTest {
        val fixture = fixture()
        val address = fixture.recipientAddress
        val trustedIdentity = IdentityKey(decode(fixture.recipientUpload.identityKey))
        fixture.senderStore.saveIdentity(address, trustedIdentity)
        val replacementUpload = generateLocalKeys(TestStore())
        fixture.accounts.bundles[fixture.recipientUser.username] = replacementUpload.toBundle(
            RECIPIENT_ACCOUNT,
            RECIPIENT_DEVICE,
        )

        val error = assertFailsWith<IdentityChangedException> {
            fixture.sender.encryptText(fixture.recipientUser, messageId(), "blocked")
        }

        assertEquals(RECIPIENT_ACCOUNT, error.accountId)
        assertEquals(trustedIdentity, fixture.senderStore.getIdentity(address))
        assertTrue(!fixture.senderStore.containsSession(address))
        assertNull(fixture.senderStore.localText("remote_device:$RECIPIENT_ACCOUNT"))
    }

    @Test
    fun `identity acceptance uses the reviewed replacement bundle`() = runTest {
        val fixture = fixture()
        establishSessions(fixture)
        val address = fixture.recipientAddress
        val replacementUpload = generateLocalKeys(TestStore())
        fixture.accounts.bundles[fixture.recipientUser.username] = replacementUpload.toBundle(
            RECIPIENT_ACCOUNT,
            RECIPIENT_DEVICE,
        )
        fixture.senderStore.deleteSession(address)
        fixture.senderStore.deleteLocalText("remote_device:$RECIPIENT_ACCOUNT")
        assertFailsWith<IdentityChangedException> {
            fixture.sender.encryptText(fixture.recipientUser, messageId(), "blocked")
        }

        val reviewed = fixture.sender.safetyNumber(fixture.recipientUser)
        val expected = SafetyNumberService().create(
            SafetyNumberParty(SENDER_ACCOUNT, SENDER_DEVICE, fixture.senderStore.identityKeyPair.publicKey),
            SafetyNumberParty(RECIPIENT_ACCOUNT, RECIPIENT_DEVICE, IdentityKey(decode(replacementUpload.identityKey))),
        )
        assertEquals(expected.displayable.displayText, reviewed.displayable.displayText)

        val unreviewedUpload = generateLocalKeys(TestStore())
        fixture.accounts.bundles[fixture.recipientUser.username] = unreviewedUpload.toBundle(
            RECIPIENT_ACCOUNT,
            RECIPIENT_DEVICE,
        )
        fixture.sender.acceptChangedIdentity(fixture.recipientUser)

        assertEquals(IdentityKey(decode(replacementUpload.identityKey)), fixture.senderStore.getIdentity(address))
        fixture.sender.encryptText(fixture.recipientUser, messageId(), "accepted")
    }

    @Test
    fun `invalid replacement signatures cannot be reviewed or trusted`() = runTest {
        val fixture = fixture()
        establishSessions(fixture)
        val address = fixture.recipientAddress
        val trustedIdentity = fixture.senderStore.getIdentity(address)
        val replacementUpload = generateLocalKeys(TestStore())
        val invalidUpload = replacementUpload.copy(
            signedPreKey = replacementUpload.signedPreKey.copy(signature = fixture.recipientUpload.signedPreKey.signature),
        )
        fixture.accounts.bundles[fixture.recipientUser.username] = replacementUpload.toBundle(
            RECIPIENT_ACCOUNT,
            RECIPIENT_DEVICE,
        )
        fixture.senderStore.deleteSession(address)
        fixture.senderStore.deleteLocalText("remote_device:$RECIPIENT_ACCOUNT")
        assertFailsWith<IdentityChangedException> {
            fixture.sender.encryptText(fixture.recipientUser, messageId(), "blocked")
        }
        fixture.accounts.bundles[fixture.recipientUser.username] = invalidUpload.toBundle(
            RECIPIENT_ACCOUNT,
            RECIPIENT_DEVICE,
        )

        assertFailsWith<InvalidKeyException> { fixture.sender.safetyNumber(fixture.recipientUser) }
        assertFailsWith<InvalidKeyException> { fixture.sender.acceptChangedIdentity(fixture.recipientUser) }
        assertEquals(trustedIdentity, fixture.senderStore.getIdentity(address))
    }

    @Test
    fun `bundle for another account is rejected without creating a session`() = runTest {
        val fixture = fixture()
        fixture.accounts.bundles[fixture.recipientUser.username] = fixture.recipientUpload.toBundle(
            SENDER_ACCOUNT,
            RECIPIENT_DEVICE,
        )

        assertFailsWith<IdentityChangedException> {
            fixture.sender.encryptText(fixture.recipientUser, messageId(), "blocked")
        }

        val wrongAddress = SignalProtocolAddress(protocolName(SENDER_ACCOUNT, RECIPIENT_DEVICE), 1)
        assertNull(fixture.senderStore.getIdentity(wrongAddress))
        assertTrue(!fixture.senderStore.containsSession(wrongAddress))
    }

    private suspend fun establishSessions(fixture: Fixture) {
        val initial = fixture.sender.encryptText(fixture.recipientUser, messageId(), "initial")
        fixture.recipient.decrypt(initial.incoming(SENDER_ACCOUNT, SENDER_DEVICE))
        val reply = fixture.recipient.encryptText(fixture.senderUser, messageId(), "reply")
        fixture.sender.decrypt(reply.incoming(RECIPIENT_ACCOUNT, RECIPIENT_DEVICE))
    }

    private fun fixture(): Fixture {
        val senderStore = TestStore()
        val recipientStore = TestStore()
        val senderUpload = generateLocalKeys(senderStore)
        val recipientUpload = generateLocalKeys(recipientStore)
        val recipientUser = UserResponse(RECIPIENT_ACCOUNT, "recipient")
        val senderUser = UserResponse(SENDER_ACCOUNT, "sender")
        val accounts = FakeAccounts().apply {
            bundles[recipientUser.username] = recipientUpload.toBundle(RECIPIENT_ACCOUNT, RECIPIENT_DEVICE)
            bundles[senderUser.username] = senderUpload.toBundle(SENDER_ACCOUNT, SENDER_DEVICE)
        }
        return Fixture(
            sender = E2eeManager(senderStore, accounts, { senderUpload }, json),
            recipient = E2eeManager(recipientStore, accounts, { recipientUpload }, json),
            senderStore = senderStore,
            accounts = accounts,
            recipientUser = recipientUser,
            senderUser = senderUser,
            recipientUpload = recipientUpload,
        )
    }

    private data class Fixture(
        val sender: E2eeManager,
        val recipient: E2eeManager,
        val senderStore: TestStore,
        val accounts: FakeAccounts,
        val recipientUser: UserResponse,
        val senderUser: UserResponse,
        val recipientUpload: DeviceKeysUpload,
    ) {
        val recipientAddress = SignalProtocolAddress(protocolName(RECIPIENT_ACCOUNT, RECIPIENT_DEVICE), 1)
    }

    private class TestStore : E2eeStore, SignalProtocolStore by InMemorySignalProtocolStore(
        IdentityKeyPair.generate(),
        123,
    ) {
        private val values = mutableMapOf<String, String>()

        override fun acceptIdentitySession(
            address: SignalProtocolAddress,
            identity: IdentityKey,
            session: SessionRecord,
            accountId: String,
            deviceId: String,
        ) {
            saveIdentity(address, identity)
            storeSession(address, session)
            putLocalText("remote_device:$accountId", deviceId)
        }

        override fun localText(key: String): String? = values[key]

        override fun putLocalText(key: String, value: String) {
            values[key] = value
        }

        override fun deleteLocalText(key: String) {
            values.remove(key)
        }
    }

    private class FakeAccounts : E2eeAccounts {
        private val localSession = TokenResponse("access", "refresh", 1, 2, SENDER_ACCOUNT, SENDER_DEVICE, "sender")

        override suspend fun restore(): TokenResponse = localSession

        val bundles = mutableMapOf<String, PreKeyBundleResponse>()
        val uploadRequests = mutableListOf<DeviceKeysUpload>()
        var uploadAttempts = 0
        var failUploads = 0
        var uploadStarted: CompletableDeferred<Unit>? = null
        var continueUpload: CompletableDeferred<Unit>? = null
        var uploadThread: Thread? = null
        var fetchThread: Thread? = null

        override suspend fun uploadKeys(request: DeviceKeysUpload) {
            uploadThread = Thread.currentThread()
            uploadAttempts++
            uploadRequests += request
            uploadStarted?.complete(Unit)
            continueUpload?.await()
            if (failUploads > 0) {
                failUploads--
                throw IOException("offline")
            }
        }

        override suspend fun fetchPreKeyBundle(username: String): PreKeyBundleResponse {
            fetchThread = Thread.currentThread()
            return bundles.getValue(username)
        }
    }

    private companion object {
        const val SENDER_ACCOUNT = "10000000-0000-0000-0000-000000000001"
        const val SENDER_DEVICE = "10000000-0000-0000-0000-000000000002"
        const val RECIPIENT_ACCOUNT = "20000000-0000-0000-0000-000000000001"
        const val RECIPIENT_DEVICE = "20000000-0000-0000-0000-000000000002"

        fun messageId(): String = UUID.randomUUID().toString()

        fun decode(value: String): ByteArray = Base64.getDecoder().decode(value)

        fun SendMessageRequest.incoming(senderAccount: String, senderDevice: String) = IncomingMessage(
            messageId = messageId,
            senderAccountId = senderAccount,
            senderDeviceId = senderDevice,
            ciphertextType = ciphertextType,
            ciphertext = ciphertext,
            createdAt = 0,
        )

        fun DeviceKeysUpload.toBundle(
            accountId: String,
            deviceId: String,
            includePreKey: Boolean = true,
            useLastResortKyber: Boolean = false,
        ) = PreKeyBundleResponse(
            accountId = accountId,
            deviceId = deviceId,
            registrationId = registrationId,
            protocolDeviceId = 1,
            identityKey = identityKey,
            signedPreKey = signedPreKey,
            preKey = if (includePreKey) preKeys.single() else null,
            kyberPreKey = if (useLastResortKyber) lastResortKyberPreKey else kyberPreKeys.single(),
            remainingPreKeys = 0,
        )

        fun generateLocalKeys(store: SignalProtocolStore): DeviceKeysUpload {
            val identity = store.identityKeyPair
            val signedId = 1
            val signedPair = ECKeyPair.generate()
            val signedSignature = identity.privateKey.calculateSignature(signedPair.publicKey.serialize())
            store.storeSignedPreKey(
                signedId,
                SignedPreKeyRecord(signedId, 1, signedPair, signedSignature),
            )

            val lastResortId = 2
            val lastResortPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val lastResortSignature = identity.privateKey.calculateSignature(lastResortPair.publicKey.serialize())
            store.storeKyberPreKey(
                lastResortId,
                KyberPreKeyRecord(lastResortId, 1, lastResortPair, lastResortSignature),
            )

            val preKeyId = 3
            val preKeyPair = ECKeyPair.generate()
            store.storePreKey(preKeyId, PreKeyRecord(preKeyId, preKeyPair))

            val kyberPreKeyId = 4
            val kyberPair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
            val kyberSignature = identity.privateKey.calculateSignature(kyberPair.publicKey.serialize())
            store.storeKyberPreKey(
                kyberPreKeyId,
                KyberPreKeyRecord(kyberPreKeyId, 1, kyberPair, kyberSignature),
            )

            return DeviceKeysUpload(
                registrationId = store.localRegistrationId,
                identityKey = identity.publicKey.serialize().base64(),
                signedPreKey = SignedPreKeyDto(
                    signedId,
                    signedPair.publicKey.serialize().base64(),
                    signedSignature.base64(),
                ),
                preKeys = listOf(PreKeyDto(preKeyId, preKeyPair.publicKey.serialize().base64())),
                kyberPreKeys = listOf(
                    KyberPreKeyDto(
                        kyberPreKeyId,
                        kyberPair.publicKey.serialize().base64(),
                        kyberSignature.base64(),
                    ),
                ),
                lastResortKyberPreKey = KyberPreKeyDto(
                    lastResortId,
                    lastResortPair.publicKey.serialize().base64(),
                    lastResortSignature.base64(),
                ),
            )
        }
    }
}
