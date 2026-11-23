package dev.securemessenger.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
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

@Database(entities = [DraftEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun drafts(): DraftDao
}
