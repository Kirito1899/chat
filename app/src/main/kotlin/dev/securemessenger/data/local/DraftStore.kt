package dev.securemessenger.data.local

import kotlinx.coroutines.flow.Flow

class DraftStore(private val database: AppDatabase) {
    suspend fun save(chatId: String, body: String) {
        database.drafts().upsert(DraftEntity(chatId, body, System.currentTimeMillis()))
    }

    suspend fun get(chatId: String): String = database.drafts().body(chatId).orEmpty()

    fun observeAll(): Flow<List<DraftEntity>> = database.drafts().observeAll()
}
