package dev.securemessenger.data.e2ee

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dev.securemessenger.data.local.AppDatabase
import org.signal.libsignal.protocol.IdentityKey
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.InvalidKeyIdException
import org.signal.libsignal.protocol.NoSessionException
import org.signal.libsignal.protocol.ReusedBaseKeyException
import org.signal.libsignal.protocol.SignalProtocolAddress
import org.signal.libsignal.protocol.ecc.ECKeyPair
import org.signal.libsignal.protocol.ecc.ECPublicKey
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord
import org.signal.libsignal.protocol.state.IdentityKeyStore
import org.signal.libsignal.protocol.state.KyberPreKeyRecord
import org.signal.libsignal.protocol.state.PreKeyRecord
import org.signal.libsignal.protocol.state.SessionRecord
import org.signal.libsignal.protocol.state.SignalProtocolStore
import org.signal.libsignal.protocol.state.SignedPreKeyRecord
import java.nio.ByteBuffer
import java.util.UUID

class PersistentSignalStore(
    database: AppDatabase,
    initialIdentity: IdentityKeyPair,
    initialRegistrationId: Int,
) : SignalProtocolStore {
    private val db = database.openHelper.writableDatabase
    private val localIdentity: IdentityKeyPair
    private val registrationId: Int

    init {
        val identity = db.blob("SELECT value FROM signal_local WHERE key = 'identity'")
        localIdentity = if (identity == null) {
            db.execSQL("INSERT INTO signal_local(key, value) VALUES ('identity', ?)", arrayOf(initialIdentity.serialize()))
            db.execSQL("INSERT INTO signal_local(key, value) VALUES ('registration_id', ?)", arrayOf(ByteBuffer.allocate(4).putInt(initialRegistrationId).array()))
            initialIdentity
        } else {
            IdentityKeyPair(identity)
        }
        registrationId = ByteBuffer.wrap(db.blob("SELECT value FROM signal_local WHERE key = 'registration_id'")!!).int
    }

    @Synchronized
    override fun getIdentityKeyPair(): IdentityKeyPair = localIdentity

    @Synchronized
    override fun getLocalRegistrationId(): Int = registrationId

    @Synchronized
    override fun saveIdentity(address: SignalProtocolAddress, identityKey: IdentityKey): IdentityKeyStore.IdentityChange {
        val previous = getIdentity(address)
        db.upsert("signal_identities", listOf("name" to address.name, "device_id" to address.deviceId, "identity" to identityKey.serialize()))
        return if (previous == null || previous == identityKey) {
            IdentityKeyStore.IdentityChange.NEW_OR_UNCHANGED
        } else {
            IdentityKeyStore.IdentityChange.REPLACED_EXISTING
        }
    }

    @Synchronized
    override fun isTrustedIdentity(address: SignalProtocolAddress, identityKey: IdentityKey, direction: IdentityKeyStore.Direction): Boolean {
        val existing = getIdentity(address) ?: return true
        return existing == identityKey
    }

    @Synchronized
    override fun getIdentity(address: SignalProtocolAddress): IdentityKey? =
        db.blob("SELECT identity FROM signal_identities WHERE name = ? AND device_id = ?", arrayOf(address.name, address.deviceId))?.let(::IdentityKey)

    @Synchronized
    override fun loadPreKey(preKeyId: Int): PreKeyRecord = db.blob("SELECT record FROM signal_prekeys WHERE kind = 'EC' AND key_id = ?", arrayOf(preKeyId))
        ?.let(::PreKeyRecord)
        ?: throw InvalidKeyIdException("No EC prekey $preKeyId")

    @Synchronized
    override fun storePreKey(preKeyId: Int, record: PreKeyRecord) {
        db.upsert("signal_prekeys", listOf("kind" to "EC", "key_id" to preKeyId, "record" to record.serialize()))
    }

    @Synchronized
    override fun containsPreKey(preKeyId: Int): Boolean = db.exists("SELECT 1 FROM signal_prekeys WHERE kind = 'EC' AND key_id = ?", arrayOf(preKeyId))

    @Synchronized
    override fun removePreKey(preKeyId: Int) {
        db.delete("signal_prekeys", "kind = 'EC' AND key_id = ?", arrayOf(preKeyId.toString()))
    }

    @Synchronized
    override fun loadSession(address: SignalProtocolAddress): SessionRecord? =
        db.blob("SELECT record FROM signal_sessions WHERE name = ? AND device_id = ?", arrayOf(address.name, address.deviceId))?.let(::SessionRecord)

    @Synchronized
    override fun loadExistingSessions(addresses: List<SignalProtocolAddress>): List<SessionRecord> = addresses.map { address ->
        loadSession(address) ?: throw NoSessionException(address, "No session for $address")
    }

    @Synchronized
    override fun getSubDeviceSessions(name: String): List<Int> = db.ints("SELECT device_id FROM signal_sessions WHERE name = ? AND device_id != 1", arrayOf(name))

    @Synchronized
    override fun storeSession(address: SignalProtocolAddress, record: SessionRecord) {
        db.upsert("signal_sessions", listOf("name" to address.name, "device_id" to address.deviceId, "record" to record.serialize()))
    }

    @Synchronized
    override fun containsSession(address: SignalProtocolAddress): Boolean =
        db.exists("SELECT 1 FROM signal_sessions WHERE name = ? AND device_id = ?", arrayOf(address.name, address.deviceId))

    @Synchronized
    override fun deleteSession(address: SignalProtocolAddress) {
        db.delete("signal_sessions", "name = ? AND device_id = ?", arrayOf(address.name, address.deviceId.toString()))
    }

    @Synchronized
    override fun deleteAllSessions(name: String) {
        db.delete("signal_sessions", "name = ?", arrayOf(name))
    }

    @Synchronized
    override fun loadSignedPreKey(signedPreKeyId: Int): SignedPreKeyRecord =
        db.blob("SELECT record FROM signal_prekeys WHERE kind = 'SIGNED' AND key_id = ?", arrayOf(signedPreKeyId))
            ?.let(::SignedPreKeyRecord)
            ?: throw InvalidKeyIdException("No signed prekey $signedPreKeyId")

    @Synchronized
    override fun loadSignedPreKeys(): List<SignedPreKeyRecord> = db.blobs("SELECT record FROM signal_prekeys WHERE kind = 'SIGNED'").map(::SignedPreKeyRecord)

    @Synchronized
    override fun storeSignedPreKey(signedPreKeyId: Int, record: SignedPreKeyRecord) {
        db.upsert("signal_prekeys", listOf("kind" to "SIGNED", "key_id" to signedPreKeyId, "record" to record.serialize()))
    }

    @Synchronized
    override fun containsSignedPreKey(signedPreKeyId: Int): Boolean = db.exists("SELECT 1 FROM signal_prekeys WHERE kind = 'SIGNED' AND key_id = ?", arrayOf(signedPreKeyId))

    @Synchronized
    override fun removeSignedPreKey(signedPreKeyId: Int) {
        db.delete("signal_prekeys", "kind = 'SIGNED' AND key_id = ?", arrayOf(signedPreKeyId.toString()))
    }

    @Synchronized
    override fun loadKyberPreKey(kyberPreKeyId: Int): KyberPreKeyRecord =
        db.blob("SELECT record FROM signal_prekeys WHERE kind = 'KYBER' AND key_id = ?", arrayOf(kyberPreKeyId))
            ?.let(::KyberPreKeyRecord)
            ?: throw InvalidKeyIdException("No Kyber prekey $kyberPreKeyId")

    @Synchronized
    override fun loadKyberPreKeys(): List<KyberPreKeyRecord> = db.blobs("SELECT record FROM signal_prekeys WHERE kind = 'KYBER'").map(::KyberPreKeyRecord)

    @Synchronized
    override fun storeKyberPreKey(kyberPreKeyId: Int, record: KyberPreKeyRecord) {
        db.upsert("signal_prekeys", listOf("kind" to "KYBER", "key_id" to kyberPreKeyId, "record" to record.serialize()))
    }

    @Synchronized
    override fun containsKyberPreKey(kyberPreKeyId: Int): Boolean = db.exists("SELECT 1 FROM signal_prekeys WHERE kind = 'KYBER' AND key_id = ?", arrayOf(kyberPreKeyId))

    @Synchronized
    override fun markKyberPreKeyUsed(kyberPreKeyId: Int, signedPreKeyId: Int, baseKey: ECPublicKey) {
        val serialized = baseKey.serialize()
        if (db.exists("SELECT 1 FROM signal_kyber_used WHERE key_id = ? AND signed_id = ? AND base_key = ?", arrayOf(kyberPreKeyId, signedPreKeyId, serialized))) {
            throw ReusedBaseKeyException()
        }
        db.upsert("signal_kyber_used", listOf("key_id" to kyberPreKeyId, "signed_id" to signedPreKeyId, "base_key" to serialized), SQLiteDatabase.CONFLICT_IGNORE)
    }

    @Synchronized
    override fun storeSenderKey(sender: SignalProtocolAddress, distributionId: UUID, record: SenderKeyRecord) {
        db.upsert("signal_sender_keys", listOf("name" to sender.name, "device_id" to sender.deviceId, "distribution_id" to distributionId.toString(), "record" to record.serialize()))
    }

    @Synchronized
    override fun loadSenderKey(sender: SignalProtocolAddress, distributionId: UUID): SenderKeyRecord? =
        db.blob("SELECT record FROM signal_sender_keys WHERE name = ? AND device_id = ? AND distribution_id = ?", arrayOf(sender.name, sender.deviceId, distributionId.toString()))?.let(::SenderKeyRecord)

    fun localText(key: String): String? = db.blob("SELECT value FROM signal_local WHERE key = ?", arrayOf(key))?.toString(Charsets.UTF_8)

    fun putLocalText(key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO signal_local(key, value) VALUES (?, ?)", arrayOf(key, value.toByteArray(Charsets.UTF_8)))
    }

    fun deleteLocalText(key: String) {
        db.delete("signal_local", "key = ?", arrayOf(key))
    }

    fun generateIdentity(): IdentityKeyPair = localIdentity

    fun nextPreKeyId(): Int {
        val current = db.blob("SELECT value FROM signal_local WHERE key = 'next_prekey_id'")?.let { ByteBuffer.wrap(it).int } ?: 1
        val next = if (current >= 0xFFFFFE) 1 else current + 1
        db.execSQL("INSERT OR REPLACE INTO signal_local(key, value) VALUES ('next_prekey_id', ?)", arrayOf(ByteBuffer.allocate(4).putInt(next).array()))
        return current
    }
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.upsert(table: String, values: List<Pair<String, Any?>>, conflict: Int = SQLiteDatabase.CONFLICT_REPLACE) {
    val content = ContentValues()
    values.forEach { (key, value) ->
        when (value) {
            is ByteArray -> content.put(key, value)
            is Int -> content.put(key, value)
            is String -> content.put(key, value)
            else -> throw IllegalArgumentException("Unsupported value")
        }
    }
    insert(table, conflict, content)
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.blob(sql: String, args: Array<out Any?> = emptyArray()): ByteArray? = query(sql, args).use { cursor ->
    if (cursor.moveToFirst()) cursor.getBlob(0) else null
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.blobs(sql: String): List<ByteArray> = query(sql).use { cursor ->
    buildList { while (cursor.moveToNext()) add(cursor.getBlob(0)) }
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.ints(sql: String, args: Array<out Any?>): List<Int> = query(sql, args).use { cursor ->
    buildList { while (cursor.moveToNext()) add(cursor.getInt(0)) }
}

private fun androidx.sqlite.db.SupportSQLiteDatabase.exists(sql: String, args: Array<out Any?>): Boolean = query(sql, args).use { it.moveToFirst() }
