package dev.securemessenger.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import dev.securemessenger.data.e2ee.SignalSchema
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey val chatId: String,
    val body: String,
    val updatedAt: Long,
)

@Dao
interface DraftDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(draft: DraftEntity)

    @Query("SELECT body FROM drafts WHERE chatId = :chatId")
    suspend fun body(chatId: String): String?

    @Query("SELECT * FROM drafts")
    fun observeAll(): Flow<List<DraftEntity>>
}

@Entity(tableName = "messages", indices = [Index("chatId")])
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val chatId: String,
    val body: String,
    val outgoing: Boolean,
    val createdAt: Long,
    val status: String,
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt, messageId")
    fun observeChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun byId(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND outgoing = 0 AND status = 'DELIVERED'")
    suspend fun unreadIncoming(chatId: String): List<MessageEntity>

    @Query("UPDATE messages SET status = 'DELIVERED' WHERE messageId = :messageId AND status != 'READ'")
    suspend fun markDelivered(messageId: String)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId AND chatId = :chatId")
    suspend fun updateStatusInChat(messageId: String, chatId: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)
}

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val messageId: String,
    val requestJson: String,
    val createdAt: Long,
)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt")
    suspend fun all(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE messageId = :messageId")
    suspend fun delete(messageId: String)
}

@Database(entities = [DraftEntity::class, MessageEntity::class, OutboxEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun drafts(): DraftDao
    abstract fun messages(): MessageDao
    abstract fun outboxes(): OutboxDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS messages(messageId TEXT NOT NULL PRIMARY KEY, chatId TEXT NOT NULL, body TEXT NOT NULL, outgoing INTEGER NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId ON messages(chatId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS outbox(messageId TEXT NOT NULL PRIMARY KEY, requestJson TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        SignalSchema.create(db)
    }
}
