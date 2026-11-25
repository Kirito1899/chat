package dev.securemessenger.data.sync

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.securemessenger.data.AuthRepository
import dev.securemessenger.data.MessageRepository
import dev.securemessenger.data.remote.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SyncService : Service() {
    @Inject lateinit var accounts: AuthRepository
    @Inject lateinit var messages: MessageRepository
    @Inject lateinit var events: ServerEventClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var running = false
    private var connection: ServerEventConnection? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        startForeground(7001, MessageNotifier.foregroundNotification(this))
        scope.launch { connectionLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        running = false
        connection?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun connectionLoop() {
        var retryDelay = 1_000L
        while (running) {
            try {
                if (accounts.restore() == null) {
                    delay(5_000)
                    continue
                }
                val connected = accounts.withValidAccessToken { token ->
                    events.connect(token) { onServerEvent() }
                }
                connection = connected
                connected.awaitOpen()
                retryDelay = 1_000L
                throw connected.awaitFailure()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (error is ApiException && error.httpStatus == 401) {
                    runCatching { accounts.withValidAccessToken { throw error } }
                }
                if (running) {
                    delay(retryDelay)
                    retryDelay = (retryDelay * 2).coerceAtMost(30_000L)
                }
            }
        }
    }

    private fun onServerEvent() {
        scope.launch {
            try {
                MessageNotifier.showMessages(this@SyncService, messages.sync())
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, SyncService::class.java))
        }
    }
}
