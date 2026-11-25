package dev.securemessenger.data.local

import dev.securemessenger.shared.UserResponse
import kotlinx.coroutines.flow.Flow

class DraftStore(private val database: AppDatabase) {
    suspend fun save(chatId: String, body: String) {
        database.drafts().upsert(DraftEntity(chatId, body, System.currentTimeMillis()))
    }

    suspend fun get(chatId: String): String = database.drafts().body(chatId).orEmpty()

    fun observeAll(): Flow<List<DraftEntity>> = database.drafts().observeAll()

    suspend fun selectedDirect(): UserResponse? {
        val accountId = get(SELECTED_DIRECT_ID)
        val username = get(SELECTED_DIRECT_USERNAME)
        if (accountId.isBlank() || username.isBlank()) return null
        return UserResponse(accountId, username)
    }

    suspend fun selectedGroupId(): String? = get(SELECTED_GROUP_ID).ifBlank { null }

    suspend fun saveSelectedDirect(user: UserResponse) {
        save(SELECTED_DIRECT_ID, user.accountId)
        save(SELECTED_DIRECT_USERNAME, user.username)
        clearSelectedGroup()
    }

    suspend fun saveSelectedGroup(groupId: String) {
        save(SELECTED_GROUP_ID, groupId)
        clearSelectedDirect()
    }

    suspend fun clearSelectedDirect() {
        save(SELECTED_DIRECT_ID, "")
        save(SELECTED_DIRECT_USERNAME, "")
    }

    suspend fun clearSelectedGroup() {
        save(SELECTED_GROUP_ID, "")
    }

    private companion object {
        const val SELECTED_DIRECT_ID = "navigation:direct:id"
        const val SELECTED_DIRECT_USERNAME = "navigation:direct:username"
        const val SELECTED_GROUP_ID = "navigation:group:id"
    }
}
