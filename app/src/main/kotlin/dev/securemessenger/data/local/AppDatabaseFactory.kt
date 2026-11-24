package dev.securemessenger.data.local

import android.content.Context
import androidx.room.Room
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.security.SecureRandom
import java.util.Base64

object AppDatabaseFactory {
    fun create(context: Context, name: String = "private_messenger.db"): AppDatabase {
        System.loadLibrary("sqlcipher")
        val keys = KeyStoreManager()
        val preferences = context.getSharedPreferences("${name}_security", Context.MODE_PRIVATE)
        val encrypted = preferences.getString("database_passphrase", null) ?: run {
            val random = ByteArray(32)
            SecureRandom().nextBytes(random)
            val value = keys.encrypt(Base64.getEncoder().encodeToString(random))
            check(preferences.edit().putString("database_passphrase", value).commit())
            value
        }
        val passphrase = Base64.getDecoder().decode(keys.decrypt(encrypted))
        return Room.databaseBuilder(context, AppDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(passphrase))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()
    }
}
