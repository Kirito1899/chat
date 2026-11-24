package dev.securemessenger.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.securemessenger.BuildConfig
import dev.securemessenger.data.GroupChatRepository
import dev.securemessenger.data.MessageRepository
import dev.securemessenger.data.e2ee.E2eeManager
import dev.securemessenger.data.e2ee.PersistentSignalStore
import dev.securemessenger.data.e2ee.PreKeyFactory
import dev.securemessenger.data.AccountRepository
import dev.securemessenger.data.AuthRepository
import dev.securemessenger.data.local.AppDatabase
import dev.securemessenger.data.local.AppDatabaseFactory
import dev.securemessenger.data.local.DataStoreSessionStore
import dev.securemessenger.data.local.DraftStore
import dev.securemessenger.data.local.KeyStoreManager
import dev.securemessenger.data.local.SessionStore
import dev.securemessenger.data.media.MediaService
import dev.securemessenger.data.media.RepositoryMediaApi
import dev.securemessenger.data.remote.MessengerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import org.signal.libsignal.protocol.IdentityKeyPair
import org.signal.libsignal.protocol.util.KeyHelper
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun json(): Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    }

    @Provides
    @Singleton
    fun preferences(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            produceFile = { context.preferencesDataStoreFile("secure_session") },
        )

    @Provides
    @Singleton
    fun keyStore(): KeyStoreManager = KeyStoreManager()

    @Provides
    @Singleton
    fun sessions(dataStore: DataStore<Preferences>, keys: KeyStoreManager, json: Json): SessionStore =
        DataStoreSessionStore(dataStore, keys, json)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): AppDatabase = AppDatabaseFactory.create(context)

    @Provides
    @Singleton
    fun drafts(database: AppDatabase): DraftStore = DraftStore(database)

    @Provides
    @Singleton
    fun okHttp(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun api(client: OkHttpClient, json: Json): MessengerApi =
        MessengerApi(BuildConfig.API_BASE_URL, client, json)

    @Provides
    @Singleton
    fun authRepository(api: MessengerApi, sessions: SessionStore): AuthRepository = AuthRepository(api, sessions)

    @Provides
    @Singleton
    fun accounts(repository: AuthRepository): AccountRepository = repository

    @Provides
    @Singleton
    fun signalStore(database: AppDatabase): PersistentSignalStore = PersistentSignalStore(
        database,
        IdentityKeyPair.generate(),
        KeyHelper.generateRegistrationId(false),
    )

    @Provides
    @Singleton
    fun preKeyFactory(): PreKeyFactory = PreKeyFactory()

    @Provides
    @Singleton
    fun e2eeManager(
        store: PersistentSignalStore,
        accounts: AuthRepository,
        preKeyFactory: PreKeyFactory,
        json: Json,
    ): E2eeManager = E2eeManager(store, accounts, preKeyFactory, json)

    @Provides
    @Singleton
    fun messages(
        database: AppDatabase,
        e2ee: E2eeManager,
        accounts: AuthRepository,
        json: Json,
    ): MessageRepository = MessageRepository(database, e2ee, accounts, json)

    @Provides
    @Singleton
    fun groupChats(
        database: AppDatabase,
        accounts: AuthRepository,
        messages: MessageRepository,
    ): GroupChatRepository = GroupChatRepository(database.groupInfo(), accounts, messages)

    @Provides
    @Singleton
    fun mediaService(
        @ApplicationContext context: Context,
        database: AppDatabase,
        accounts: AuthRepository,
    ): MediaService = MediaService(
        File(context.noBackupFilesDir, "media"),
        RepositoryMediaApi(accounts),
        database.mediaUploads(),
    )
}
