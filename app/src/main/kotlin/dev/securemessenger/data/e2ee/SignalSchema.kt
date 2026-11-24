package dev.securemessenger.data.e2ee

import androidx.sqlite.db.SupportSQLiteDatabase

object SignalSchema {
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS signal_local(key TEXT NOT NULL PRIMARY KEY, value BLOB NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS signal_identities(name TEXT NOT NULL, device_id INTEGER NOT NULL, identity BLOB NOT NULL, verified INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(name, device_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS signal_sessions(name TEXT NOT NULL, device_id INTEGER NOT NULL, record BLOB NOT NULL, PRIMARY KEY(name, device_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS signal_prekeys(kind TEXT NOT NULL, key_id INTEGER NOT NULL, record BLOB NOT NULL, PRIMARY KEY(kind, key_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS signal_kyber_used(key_id INTEGER NOT NULL, signed_id INTEGER NOT NULL, base_key BLOB NOT NULL, PRIMARY KEY(key_id, signed_id, base_key))")
        db.execSQL("CREATE TABLE IF NOT EXISTS signal_sender_keys(name TEXT NOT NULL, device_id INTEGER NOT NULL, distribution_id TEXT NOT NULL, record BLOB NOT NULL, PRIMARY KEY(name, device_id, distribution_id))")
    }
}
