package dev.securemessenger.data.local

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import dev.securemessenger.shared.TokenResponse
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import java.util.UUID

interface SessionStore {
    suspend fun read(): TokenResponse?
    suspend fun save(session: TokenResponse)
    suspend fun clear()
    suspend fun deviceId(): String
}

class DataStoreSessionStore(
    private val dataStore: DataStore<Preferences>,
    private val keyStore: KeyStoreManager,
    private val json: Json,
) : SessionStore {
    private val sessionKey = stringPreferencesKey("encrypted_session")
    private val deviceKey = stringPreferencesKey("device_id")

    override suspend fun read(): TokenResponse? {
        val preferences = dataStore.data.first()
        val encrypted = preferences[sessionKey] ?: return null
        return try {
            json.decodeFromString<TokenResponse>(keyStore.decrypt(encrypted))
        } catch (_: Exception) {
            dataStore.edit { it.remove(sessionKey) }
            null
        }
    }

    override suspend fun save(session: TokenResponse) {
        val encrypted = keyStore.encrypt(json.encodeToString(TokenResponse.serializer(), session))
        dataStore.edit {
            it[sessionKey] = encrypted
            it[deviceKey] = session.deviceId
        }
    }

    override suspend fun clear() {
        dataStore.edit { it.remove(sessionKey) }
    }

    override suspend fun deviceId(): String {
        dataStore.data.first()[deviceKey]?.let { return it }
        val created = UUID.randomUUID().toString()
        dataStore.edit { it[deviceKey] = created }
        return created
    }
}
