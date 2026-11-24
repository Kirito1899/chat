package dev.securemessenger.data.e2ee

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.securemessenger.data.local.AppDatabaseFactory
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SessionBuilder
import org.signal.libsignal.protocol.SessionCipher
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.groups.GroupSessionBuilder
import org.signal.libsignal.protocol.kem.KEMKeyPair
import org.signal.libsignal.protocol.kem.KEMKeyType
import org.signal.libsignal.protocol.message.CiphertextMessage
import org.signal.libsignal.protocol.message.PreKeySignalMessage
import org.signal.libsignal.protocol.message.SignalMessage
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyBundle
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import org.signal.libsignal.protocol.state.impl.InMemorySignalProtocolStore
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PersistentSignalStoreTest {
    private lateinit var context: Context
    private val databaseNames = mutableSetOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        databaseNames.forEach { name ->
            context.deleteDatabase(name)
            context.getSharedPreferences("${name}_security", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun migration_creates_signal_tables() {
        val database = openDatabase(newDatabaseName())
        PersistentSignalStore(database, IdentityKeyPair.generate(), 123)
        try {
            val tables = database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
            println("SIGNAL_DB_TABLES $tables")
            assertTrue("signal_local" in tables)
        } finally {
            database.close()
        }
    }

    @Test
    fun local_identity_and_all_prekey_kinds_survive_reopen() {
        val name = newDatabaseName()
        val identity = IdentityKeyPair.generate()
        val preKey = PreKeyRecord(7, ECKeyPair.generate())
        val signedPreKey = newSignedPreKey(identity, 7)
        val kyberPreKey = newKyberPreKey(identity, 7)

        val first = openDatabase(name)
        try {
            val store = PersistentSignalStore(first, identity, 123)
            store.storePreKey(7, preKey)
            store.storeSignedPreKey(7, signedPreKey)
            store.storeKyberPreKey(7, kyberPreKey)
        } finally {
            first.close()
        }

        val restoredDb = openDatabase(name)
        try {
            val restored = PersistentSignalStore(restoredDb, IdentityKeyPair.generate(), 456)
            assertEquals(identity.serialize().toList(), restored.identityKeyPair.serialize().toList())
            assertEquals(123, restored.localRegistrationId)
            assertEquals(preKey.serialize().toList(), restored.loadPreKey(7).serialize().toList())
            assertEquals(signedPreKey.serialize().toList(), restored.loadSignedPreKey(7).serialize().toList())
            assertEquals(kyberPreKey.serialize().toList(), restored.loadKyberPreKey(7).serialize().toList())
            assertEquals(listOf(7), restored.loadSignedPreKeys().map { it.id })
            assertEquals(listOf(7), restored.loadKyberPreKeys().map { it.id })

            restored.removePreKey(7)
            restored.removeSignedPreKey(7)
            assertFalse(restored.containsPreKey(7))
            assertFalse(restored.containsSignedPreKey(7))
            assertTrue(restored.containsKyberPreKey(7))
            assertFailsWith<InvalidKeyIdException> { restored.loadPreKey(7) }
            assertFailsWith<InvalidKeyIdException> { restored.loadSignedPreKey(7) }
        } finally {
            restoredDb.close()
        }
    }

    @Test
    fun partially_initialized_local_identity_is_recovered_without_replacement() {
        val name = newDatabaseName()
        val identity = IdentityKeyPair.generate()
        val first = openDatabase(name)
        SignalSchema.create(first.openHelper.writableDatabase)
        try {
            first.openHelper.writableDatabase.execSQL(
                "INSERT INTO signal_local(key, value) VALUES ('identity', ?)",
                arrayOf(identity.serialize()),
            )
        } finally {
            first.close()
        }

        val restoredDb = openDatabase(name)
        try {
            val restored = PersistentSignalStore(restoredDb, IdentityKeyPair.generate(), 456)
            assertEquals(identity.serialize().toList(), restored.identityKeyPair.serialize().toList())
            assertEquals(456, restored.localRegistrationId)
        } finally {
            restoredDb.close()
        }
    }

    @Test
    fun identity_changes_and_trust_match_libsignal_semantics() {
        val database = openDatabase(newDatabaseName())
        try {
            val store = PersistentSignalStore(database, IdentityKeyPair.generate(), 123)
            val address = SignalProtocolAddress("remote-account:remote-device", 1)
            val original = IdentityKeyPair.generate().publicKey
            val replacement = IdentityKeyPair.generate().publicKey

            assertTrue(store.isTrustedIdentity(address, original, IdentityKeyStore.Direction.SENDING))
            assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(address, original))
            assertEquals(IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED, store.saveIdentity(address, original))
            assertTrue(store.isTrustedIdentity(address, original, IdentityKeyStore.Direction.SENDING))
            assertTrue(store.isTrustedIdentity(address, original, IdentityKeyStore.Direction.RECEIVING))
            assertFalse(store.isTrustedIdentity(address, replacement, IdentityKeyStore.Direction.SENDING))
            assertFalse(store.isTrustedIdentity(address, replacement, IdentityKeyStore.Direction.RECEIVING))

            assertEquals(IdentityKeyStore.IdentityChange.REPLACED_EXISTING, store.saveIdentity(address, replacement))
            assertEquals(replacement, store.getIdentity(address))
            assertTrue(store.isTrustedIdentity(address, replacement, IdentityKeyStore.Direction.SENDING))
            assertFalse(store.isTrustedIdentity(address, original, IdentityKeyStore.Direction.SENDING))
        } finally {
            database.close()
        }
    }

    @Test
    fun loaded_session_changes_are_not_persisted_without_storeSession() {
        val fixture = establishedPqxdhSession()
        val original = fixture.alice.loadSession(fixture.bobAddress)!!
        val originalBytes = original.serialize()
        val database = openDatabase(newDatabaseName())
        try {
            val store = PersistentSignalStore(database, IdentityKeyPair.generate(), 123)
            store.storeSession(fixture.bobAddress, original)

            val changed = store.loadSession(fixture.bobAddress)!!
            changed.archiveCurrentState()
            val changedBytes = changed.serialize()
            assertFalse(originalBytes.contentEquals(changedBytes))
            assertEquals(originalBytes.toList(), store.loadSession(fixture.bobAddress)!!.serialize().toList())

            store.storeSession(fixture.bobAddress, changed)
            assertEquals(changedBytes.toList(), store.loadSession(fixture.bobAddress)!!.serialize().toList())
        } finally {
            database.close()
        }
    }

    @Test
    fun session_lookup_and_deletion_are_scoped_to_protocol_addresses() {
        val database = openDatabase(newDatabaseName())
        try {
            val store = PersistentSignalStore(database, IdentityKeyPair.generate(), 123)
            val primary = SignalProtocolAddress("alice", 1)
            val secondary = SignalProtocolAddress("alice", 2)
            val other = SignalProtocolAddress("bob", 2)
            val primaryRecord = SessionRecord()
            val secondaryRecord = SessionRecord()
            store.storeSession(primary, primaryRecord)
            store.storeSession(secondary, secondaryRecord)
            store.storeSession(other, SessionRecord())

            assertTrue(store.containsSession(primary))
            assertEquals(listOf(2), store.getSubDeviceSessions("alice"))
            assertEquals(
                listOf(primaryRecord.serialize().toList(), secondaryRecord.serialize().toList()),
                store.loadExistingSessions(listOf(primary, secondary)).map { it.serialize().toList() },
            )
            assertFailsWith<NoSessionException> {
                store.loadExistingSessions(listOf(primary, SignalProtocolAddress("alice", 3)))
            }

            store.deleteSession(primary)
            assertFalse(store.containsSession(primary))
            assertTrue(store.containsSession(secondary))
            store.deleteAllSessions("alice")
            assertFalse(store.containsSession(secondary))
            assertTrue(store.containsSession(other))
        } finally {
            database.close()
        }
    }

    @Test
    fun kyber_base_key_replay_is_scoped_to_the_full_tuple_and_survives_reopen() {
        val name = newDatabaseName()
        val baseKey = ECKeyPair.generate().publicKey
        val first = openDatabase(name)
        try {
            val store = PersistentSignalStore(first, IdentityKeyPair.generate(), 123)
            store.markKyberPreKeyUsed(9, 10, baseKey)
            store.markKyberPreKeyUsed(9, 11, baseKey)
            store.markKyberPreKeyUsed(9, 10, ECKeyPair.generate().publicKey)
            assertFailsWith<ReusedBaseKeyException> { store.markKyberPreKeyUsed(9, 10, baseKey) }
        } finally {
            first.close()
        }

        val restoredDb = openDatabase(name)
        try {
            val restored = PersistentSignalStore(restoredDb, IdentityKeyPair.generate(), 123)
            assertFailsWith<ReusedBaseKeyException> { restored.markKyberPreKeyUsed(9, 10, baseKey) }
        } finally {
            restoredDb.close()
        }
    }

    @Test
    fun sender_keys_are_persistent_and_distribution_specific() {
        val name = newDatabaseName()
        val sender = SignalProtocolAddress("group-sender", 1)
        val distributionId = UUID.randomUUID()
        val expected: ByteArray
        val first = openDatabase(name)
        try {
            val store = PersistentSignalStore(first, IdentityKeyPair.generate(), 123)
            GroupSessionBuilder(store).create(sender, distributionId)
            expected = store.loadSenderKey(sender, distributionId)!!.serialize()
            assertNull(store.loadSenderKey(sender, UUID.randomUUID()))
        } finally {
            first.close()
        }

        val restoredDb = openDatabase(name)
        try {
            val restored = PersistentSignalStore(restoredDb, IdentityKeyPair.generate(), 123)
            assertEquals(expected.toList(), restored.loadSenderKey(sender, distributionId)!!.serialize().toList())
            assertNull(restored.loadSenderKey(SignalProtocolAddress("other-sender", 1), distributionId))
        } finally {
            restoredDb.close()
        }
    }

    @Test
    fun local_text_survives_reopen_and_is_not_plaintext_in_the_database_file() {
        val name = newDatabaseName()
        val marker = "local plaintext marker: pending_upload ✓"
        val first = openDatabase(name)
        try {
            val store = PersistentSignalStore(first, IdentityKeyPair.generate(), 123)
            assertNull(store.localText("missing"))
            store.putLocalText("draft", "old")
            store.putLocalText("draft", marker)
            store.putLocalText("deleted", "remove me")
            store.deleteLocalText("deleted")
            assertEquals(marker, store.localText("draft"))
            assertNull(store.localText("deleted"))
        } finally {
            first.close()
        }

        val rawDatabase = context.getDatabasePath(name).readBytes()
        assertTrue(rawDatabase.isNotEmpty())
        assertFalse(rawDatabase.containsBytes(marker.toByteArray(Charsets.UTF_8)))
        assertFalse(rawDatabase.containsBytes("SQLite format 3".toByteArray(Charsets.US_ASCII)))

        val restoredDb = openDatabase(name)
        try {
            val restored = PersistentSignalStore(restoredDb, IdentityKeyPair.generate(), 123)
            assertEquals(marker, restored.localText("draft"))
            assertNull(restored.localText("deleted"))
        } finally {
            restoredDb.close()
        }
    }

    @Test
    fun prekey_ids_advance_persist_and_skip_the_reserved_maximum() {
        val name = newDatabaseName()
        val database = openDatabase(name)
        try {
            val store = PersistentSignalStore(database, IdentityKeyPair.generate(), 123)
            assertEquals(1, store.nextPreKeyId())
            assertEquals(2, store.nextPreKeyId())
            database.openHelper.writableDatabase.execSQL(
                "INSERT OR REPLACE INTO signal_local(key, value) VALUES ('next_prekey_id', ?)",
                arrayOf(ByteBuffer.allocate(4).putInt(0xFFFFFE).array()),
            )
            assertEquals(0xFFFFFE, store.nextPreKeyId())
            assertEquals(1, store.nextPreKeyId())
        } finally {
            database.close()
        }

        val restoredDb = openDatabase(name)
        try {
            val restored = PersistentSignalStore(restoredDb, IdentityKeyPair.generate(), 123)
            assertEquals(2, restored.nextPreKeyId())
        } finally {
            restoredDb.close()
        }
    }

    @Test
    fun native_pqxdh_prekey_and_whisper_messages_round_trip_between_in_memory_stores() {
        val fixture = establishedPqxdhSession()
        val aliceCipher = SessionCipher(fixture.alice, fixture.bobAddress)
        val bobCipher = SessionCipher(fixture.bob, fixture.aliceAddress)
        val first = "first native PQXDH message".toByteArray(Charsets.UTF_8)
        val second = "subsequent Double Ratchet message".toByteArray(Charsets.UTF_8)

        val preKeyCiphertext = aliceCipher.encrypt(first)
        assertEquals(CiphertextMessage.PREKEY_TYPE, preKeyCiphertext.type)
        val firstDecrypted = bobCipher.decrypt(PreKeySignalMessage(preKeyCiphertext.serialize()))
        assertEquals(first.toList(), firstDecrypted.toList())
        val acknowledgement = aliceCipher.decrypt(SignalMessage(bobCipher.encrypt("handshake acknowledgement".toByteArray()).serialize()))

        val whisperCiphertext = aliceCipher.encrypt(second)
        assertEquals(CiphertextMessage.WHISPER_TYPE, whisperCiphertext.type)
        val secondDecrypted = bobCipher.decrypt(SignalMessage(whisperCiphertext.serialize()))
        assertEquals(second.toList(), secondDecrypted.toList())
    }

    @Test
    fun accepted_identity_session_and_device_binding_survive_reopen() {
        val name = newDatabaseName()
        val address = SignalProtocolAddress("accepted-account:accepted-device", 1)
        val identity = IdentityKeyPair.generate().publicKey
        val first = openDatabase(name)
        try {
            PersistentSignalStore(first, IdentityKeyPair.generate(), 123).acceptIdentitySession(
                address,
                identity,
                SessionRecord(),
                "accepted-account",
                "accepted-device",
            )
        } finally {
            first.close()
        }

        val restoredDb = openDatabase(name)
        try {
            val restored = PersistentSignalStore(restoredDb, IdentityKeyPair.generate(), 123)
            assertEquals(identity, restored.getIdentity(address))
            assertTrue(restored.loadSession(address) != null)
            assertEquals("accepted-device", restored.localText("remote_device:accepted-account"))
        } finally {
            restoredDb.close()
        }
    }

    private fun establishedPqxdhSession(): PqxdhFixture {
        val alice = InMemorySignalProtocolStore(IdentityKeyPair.generate(), 123)
        val bob = InMemorySignalProtocolStore(IdentityKeyPair.generate(), 456)
        val aliceAddress = SignalProtocolAddress("alice-account:alice-device", 1)
        val bobAddress = SignalProtocolAddress("bob-account:bob-device", 1)
        val preKeyPair = ECKeyPair.generate()
        val signedPreKey = newSignedPreKey(bob.identityKeyPair, 8)
        val kyberPreKey = newKyberPreKey(bob.identityKeyPair, 9)
        bob.storePreKey(7, PreKeyRecord(7, preKeyPair))
        bob.storeSignedPreKey(8, signedPreKey)
        bob.storeKyberPreKey(9, kyberPreKey)

        SessionBuilder(alice, bobAddress).process(
            PreKeyBundle(
                456,
                1,
                7,
                preKeyPair.publicKey,
                8,
                signedPreKey.keyPair.publicKey,
                signedPreKey.signature,
                bob.identityKeyPair.publicKey,
                9,
                kyberPreKey.keyPair.publicKey,
                kyberPreKey.signature,
            ),
        )
        return PqxdhFixture(alice, bob, aliceAddress, bobAddress)
    }

    private fun newSignedPreKey(identity: IdentityKeyPair, id: Int): SignedPreKeyRecord {
        val pair = ECKeyPair.generate()
        return SignedPreKeyRecord(
            id,
            1_234L,
            pair,
            identity.privateKey.calculateSignature(pair.publicKey.serialize()),
        )
    }

    private fun newKyberPreKey(identity: IdentityKeyPair, id: Int): KyberPreKeyRecord {
        val pair = KEMKeyPair.generate(KEMKeyType.KYBER_1024)
        return KyberPreKeyRecord(
            id,
            5_678L,
            pair,
            identity.privateKey.calculateSignature(pair.publicKey.serialize()),
        )
    }

    private fun newDatabaseName(): String = "signal-${UUID.randomUUID()}".also(databaseNames::add)

    private fun openDatabase(name: String) = AppDatabaseFactory.create(context, name)

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        for (offset in 0..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[offset + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private data class PqxdhFixture(
        val alice: InMemorySignalProtocolStore,
        val bob: InMemorySignalProtocolStore,
        val aliceAddress: SignalProtocolAddress,
        val bobAddress: SignalProtocolAddress,
    )
}
