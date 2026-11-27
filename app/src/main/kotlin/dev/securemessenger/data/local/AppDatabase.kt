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
import java.util.UUID

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
    val mediaJson: String? = null,
    val senderAccountId: String? = null,
)

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt, messageId")
    fun observeChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE messageId = :messageId")
    suspend fun byId(messageId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE messageId = :messageId AND chatId = :chatId")
    suspend fun byIdInChat(messageId: String, chatId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND outgoing = 0 AND status = 'DELIVERED'")
    suspend fun unreadIncoming(chatId: String): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt DESC, messageId DESC LIMIT 1")
    suspend fun latestInChat(chatId: String): MessageEntity?

    @Query("UPDATE messages SET status = 'DELIVERED' WHERE messageId = :messageId AND status != 'READ'")
    suspend fun markDelivered(messageId: String)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId AND chatId = :chatId")
    suspend fun updateStatusInChat(messageId: String, chatId: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: String, status: String)
}

@Entity(tableName = "direct_conversations", primaryKeys = ["ownerAccountId", "accountId"], indices = [Index("ownerAccountId")])
data class DirectConversationEntity(
    val ownerAccountId: String,
    val accountId: String,
    val username: String,
    val notificationKey: String = UUID.randomUUID().toString(),
    val lastBody: String = "",
    val lastCreatedAt: Long = 0,
    val lastOutgoing: Boolean = false,
    val unreadCount: Int = 0,
)

@Dao
interface DirectConversationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun create(item: DirectConversationEntity): Long

    @Query("""
        UPDATE direct_conversations
        SET lastBody = :body, lastCreatedAt = :createdAt, lastOutgoing = :outgoing,
            unreadCount = unreadCount + :unreadIncrement
        WHERE ownerAccountId = :ownerAccountId AND accountId = :accountId
    """)
    suspend fun updateLastMessage(
        ownerAccountId: String,
        accountId: String,
        body: String,
        createdAt: Long,
        outgoing: Boolean,
        unreadIncrement: Int,
    )

    @Query("UPDATE direct_conversations SET unreadCount = :count WHERE ownerAccountId = :ownerAccountId AND accountId = :accountId")
    suspend fun setUnreadCount(ownerAccountId: String, accountId: String, count: Int)

    @Query("SELECT * FROM direct_conversations WHERE ownerAccountId = :ownerAccountId ORDER BY lastCreatedAt DESC, accountId")
    fun observe(ownerAccountId: String): Flow<List<DirectConversationEntity>>

    @Query("SELECT * FROM direct_conversations WHERE ownerAccountId = :ownerAccountId AND notificationKey = :notificationKey")
    suspend fun byNotificationKey(ownerAccountId: String, notificationKey: String): DirectConversationEntity?

    @Query("SELECT * FROM direct_conversations WHERE ownerAccountId = :ownerAccountId AND unreadCount > 0 ORDER BY lastCreatedAt DESC LIMIT 1")
    suspend fun latestUnread(ownerAccountId: String): DirectConversationEntity?
}

@Entity(tableName = "outbox")
data class OutboxEntity(
    val messageId: String,
    val requestJson: String,
    val createdAt: Long,
    @PrimaryKey val envelopeId: String = messageId,
    val groupId: String? = null,
)

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(item: OutboxEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt")
    suspend fun all(): List<OutboxEntity>

    @Query("DELETE FROM outbox WHERE envelopeId = :envelopeId")
    suspend fun delete(envelopeId: String)
}

@Entity(tableName = "group_info")
data class GroupInfoEntity(
    @PrimaryKey val groupId: String,
    val title: String,
    val ownerAccountId: String,
)

@Dao
interface GroupInfoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(info: GroupInfoEntity)

    @Query("SELECT * FROM group_info WHERE groupId = :groupId")
    suspend fun get(groupId: String): GroupInfoEntity?

    @Query("SELECT * FROM group_info ORDER BY title")
    fun observeAll(): Flow<List<GroupInfoEntity>>
}

@Entity(tableName = "group_receipts", indices = [Index("groupMessageId", "recipientDeviceId")])
data class GroupReceiptEntity(
    @PrimaryKey val envelopeId: String,
    val groupMessageId: String,
    val recipientDeviceId: String,
    val state: String,
)

@Dao
interface GroupReceiptDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: GroupReceiptEntity): Long

    @Query("UPDATE group_receipts SET state = 'SENT' WHERE envelopeId = :envelopeId AND state = 'PENDING'")
    suspend fun markSent(envelopeId: String)

    @Query("UPDATE group_receipts SET state = 'DELIVERED' WHERE envelopeId = :envelopeId AND state IN ('PENDING', 'SENT')")
    suspend fun markDelivered(envelopeId: String)

    @Query("UPDATE group_receipts SET state = 'SKIPPED' WHERE envelopeId = :envelopeId AND state != 'READ'")
    suspend fun markSkipped(envelopeId: String)

    @Query("UPDATE group_receipts SET state = 'READ' WHERE groupMessageId = :groupMessageId AND recipientDeviceId = :recipientDeviceId")
    suspend fun markRead(groupMessageId: String, recipientDeviceId: String)

    @Query("SELECT * FROM group_receipts")
    fun observeAll(): Flow<List<GroupReceiptEntity>>
}

@Entity(tableName = "media_uploads")
data class MediaUploadEntity(
    @PrimaryKey val mediaId: String,
    val encryptedPath: String,
    val fileName: String,
    val mimeType: String,
    val plaintextBytes: Long,
    val ciphertextSha256: String,
    val key: String,
    val uploadedBytes: Long,
    val createdAt: Long,
    val destinationChatId: String? = null,
    val recipientUsername: String? = null,
    val caption: String? = null,
    val dispatched: Boolean = false,
    val messageId: String? = null,
)

@Dao
interface MediaUploadDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaUploadEntity)

    @Query("SELECT * FROM media_uploads WHERE mediaId = :mediaId")
    suspend fun get(mediaId: String): MediaUploadEntity?

    @Query("SELECT * FROM media_uploads ORDER BY createdAt")
    suspend fun all(): List<MediaUploadEntity>

    @Query("SELECT * FROM media_uploads WHERE dispatched = 0 ORDER BY createdAt")
    suspend fun pending(): List<MediaUploadEntity>

    @Query("UPDATE media_uploads SET dispatched = 1, messageId = :messageId WHERE mediaId = :mediaId AND dispatched = 0")
    suspend fun tryMarkDispatched(mediaId: String, messageId: String): Int

    @Query("DELETE FROM media_uploads WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String)
}

@Database(
    entities = [DraftEntity::class, MessageEntity::class, OutboxEntity::class, GroupInfoEntity::class, GroupReceiptEntity::class, MediaUploadEntity::class, DirectConversationEntity::class],
    version = 6,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun drafts(): DraftDao
    abstract fun messages(): MessageDao
    abstract fun outboxes(): OutboxDao
    abstract fun groupInfo(): GroupInfoDao
    abstract fun groupReceipts(): GroupReceiptDao
    abstract fun mediaUploads(): MediaUploadDao
    abstract fun directConversations(): DirectConversationDao
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS messages(messageId TEXT NOT NULL PRIMARY KEY, chatId TEXT NOT NULL, body TEXT NOT NULL, outgoing INTEGER NOT NULL, createdAt INTEGER NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_chatId ON messages(chatId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS outbox(messageId TEXT NOT NULL PRIMARY KEY, requestJson TEXT NOT NULL, createdAt INTEGER NOT NULL)")
        SignalSchema.create(db)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN mediaJson TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN senderAccountId TEXT")
        db.execSQL("CREATE TABLE outbox_new(envelopeId TEXT NOT NULL PRIMARY KEY, messageId TEXT NOT NULL, requestJson TEXT NOT NULL, createdAt INTEGER NOT NULL, groupId TEXT)")
        db.execSQL("INSERT INTO outbox_new(envelopeId, messageId, requestJson, createdAt, groupId) SELECT messageId, messageId, requestJson, createdAt, NULL FROM outbox")
        db.execSQL("DROP TABLE outbox")
        db.execSQL("ALTER TABLE outbox_new RENAME TO outbox")
        db.execSQL("CREATE TABLE group_info(groupId TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, ownerAccountId TEXT NOT NULL)")
        db.execSQL("CREATE TABLE group_receipts(envelopeId TEXT NOT NULL PRIMARY KEY, groupMessageId TEXT NOT NULL, recipientDeviceId TEXT NOT NULL, state TEXT NOT NULL)")
        db.execSQL("CREATE INDEX index_group_receipts_groupMessageId_recipientDeviceId ON group_receipts(groupMessageId, recipientDeviceId)")
        SignalSchema.create(db)
    }
}


val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE media_uploads ADD COLUMN destinationChatId TEXT")
        db.execSQL("ALTER TABLE media_uploads ADD COLUMN recipientUsername TEXT")
        db.execSQL("ALTER TABLE media_uploads ADD COLUMN caption TEXT")
        db.execSQL("ALTER TABLE media_uploads ADD COLUMN dispatched INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE media_uploads ADD COLUMN messageId TEXT")
        SignalSchema.create(db)
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS direct_conversations(
                ownerAccountId TEXT NOT NULL,
                accountId TEXT NOT NULL,
                username TEXT NOT NULL,
                notificationKey TEXT NOT NULL,
                lastBody TEXT NOT NULL,
                lastCreatedAt INTEGER NOT NULL,
                lastOutgoing INTEGER NOT NULL,
                unreadCount INTEGER NOT NULL,
                PRIMARY KEY(ownerAccountId, accountId)
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS index_direct_conversations_ownerAccountId ON direct_conversations(ownerAccountId)")
        SignalSchema.create(db)
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE media_uploads(mediaId TEXT NOT NULL PRIMARY KEY, encryptedPath TEXT NOT NULL, fileName TEXT NOT NULL, mimeType TEXT NOT NULL, plaintextBytes INTEGER NOT NULL, ciphertextSha256 TEXT NOT NULL, key TEXT NOT NULL, uploadedBytes INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
        SignalSchema.create(db)
    }
}
