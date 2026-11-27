package dev.securemessenger.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Rule
import org.junit.Test

class AppDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        SupportOpenHelperFactory("migration-test-passphrase".toByteArray()),
    )

    init {
        System.loadLibrary("sqlcipher")
    }

    @Test
    fun direct_conversations_migration_preserves_existing_drafts() {
        val name = "migration-conversations-${UUID.randomUUID()}"
        helper.createDatabase(name, 5).use { database ->
            database.execSQL("INSERT INTO drafts(chatId, body, updatedAt) VALUES ('direct:peer', 'saved draft', 123)")
        }

        helper.runMigrationsAndValidate(name, 6, false, MIGRATION_5_6).use { database ->
            database.query("SELECT body FROM drafts WHERE chatId = 'direct:peer'").use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == "saved draft")
            }
            database.query("PRAGMA table_info(direct_conversations)").use { cursor ->
                val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
                check("ownerAccountId" in columns)
                check("notificationKey" in columns)
                check("unreadCount" in columns)
            }
        }
    }

    @Test
    fun version_two_outbox_survives_migration_to_groups_and_media() {
        val name = "migration-${UUID.randomUUID()}"
        val messageId = UUID.randomUUID().toString()
        helper.createDatabase(name, 2).use { database ->
            database.execSQL("INSERT INTO outbox(messageId, requestJson, createdAt) VALUES (?, '{}', 123)", arrayOf(messageId))
        }

        // Signal tables have their own schema assertions in PersistentSignalStoreTest.
        helper.runMigrationsAndValidate(name, 4, false, MIGRATION_2_3, MIGRATION_3_4).use { database ->
            database.query("SELECT envelopeId, messageId, groupId FROM outbox WHERE messageId = ?", arrayOf(messageId)).use { cursor ->
                check(cursor.moveToFirst())
                check(cursor.getString(0) == messageId)
                check(cursor.getString(1) == messageId)
                check(cursor.isNull(2))
            }
            database.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'media_uploads'").use { cursor ->
                check(cursor.moveToFirst())
            }
            database.query("PRAGMA table_info(messages)").use { cursor ->
                val columns = buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
                check("mediaJson" in columns)
                check("senderAccountId" in columns)
            }
        }
    }
}
