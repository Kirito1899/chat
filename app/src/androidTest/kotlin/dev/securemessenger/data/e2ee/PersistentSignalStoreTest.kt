package dev.securemessenger.data.e2ee

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.securemessenger.data.local.AppDatabaseFactory
import org.junit.runner.RunWith
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class PersistentSignalStoreTest {
    @Test
    fun identity_prekeys_sessions_and_used_base_keys_survive_reopen() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "signal-${UUID.randomUUID()}"
        val identity = IdentityKeyPair.generate()
        val address = SignalProtocolAddress("remote-account:remote-device", 1)
        val remoteIdentity = IdentityKeyPair.generate().publicKey
        val preKey = PreKeyRecord(7, ECKeyPair.generate())
        val session = SessionRecord()
        val baseKey = ECKeyPair.generate().publicKey

        val first = AppDatabaseFactory.create(context, name)
        val store = PersistentSignalStore(first, identity, 123)
        store.saveIdentity(address, remoteIdentity)
        store.storePreKey(7, preKey)
        store.storeSession(address, session)
        store.markKyberPreKeyUsed(9, 10, baseKey)
        assertFailsWith<ReusedBaseKeyException> { store.markKyberPreKeyUsed(9, 10, baseKey) }
        first.close()

        val restoredDb = AppDatabaseFactory.create(context, name)
        val restored = PersistentSignalStore(restoredDb, IdentityKeyPair.generate(), 456)
        try {
            assertEquals(identity.publicKey, restored.identityKeyPair.publicKey)
            assertEquals(123, restored.localRegistrationId)
            assertEquals(remoteIdentity, restored.getIdentity(address))
            assertFalse(
                restored.isTrustedIdentity(
                    address,
                    IdentityKeyPair.generate().publicKey,
                    IdentityKeyStore.Direction.SENDING,
                ),
            )
            assertTrue(restored.containsPreKey(7))
            assertTrue(restored.containsSession(address))
            assertEquals(preKey.serialize().toList(), restored.loadPreKey(7).serialize().toList())
            assertEquals(session.serialize().toList(), restored.loadSession(address)!!.serialize().toList())
        } finally {
            restoredDb.close()
        }
    }
}
