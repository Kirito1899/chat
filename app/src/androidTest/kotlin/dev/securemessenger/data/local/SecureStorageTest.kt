package dev.securemessenger.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.securemessenger.shared.TokenResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class SecureStorageTest {
    @Test
    fun keystore_encryption_authenticates_data() {
        val keys = KeyStoreManager()
        val encrypted = keys.encrypt("secret-session")
        assertFalse(encrypted.contains("secret-session"))
        assertEquals("secret-session", keys.decrypt(encrypted))
    }

    @Test
    fun encrypted_session_and_stable_device_id_survive_store_recreation() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val keys = KeyStoreManager()
        val name = "test-session-${UUID.randomUUID()}"
        val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile(name) },
        )
        fun store(): DataStoreSessionStore = DataStoreSessionStore(dataStore, keys, Json)

        val first = store()
        val deviceId = first.deviceId()
        val session = TokenResponse("access", "refresh", 1, 2, UUID.randomUUID().toString(), deviceId, "test_user")
        first.save(session)

        assertEquals(session, store().read())
        assertEquals(deviceId, store().deviceId())
        store().clear()
        assertNull(store().read())
        assertEquals(deviceId, store().deviceId())
    }

    @Test
    fun draft_survives_database_recreation() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "test-drafts-${UUID.randomUUID()}"
        val first = AppDatabaseFactory.create(context, name)
        try {
            DraftStore(first).save("chat-1", "Незавершённый черновик")
        } finally {
            first.close()
        }

        val restored = AppDatabaseFactory.create(context, name)
        try {
            assertEquals("Незавершённый черновик", DraftStore(restored).get("chat-1"))
        } finally {
            restored.close()
        }
    }
}
