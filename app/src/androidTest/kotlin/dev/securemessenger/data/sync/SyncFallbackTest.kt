package dev.securemessenger.data.sync

import android.app.Notification
import android.app.NotificationManager
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.securemessenger.MainActivity
import dev.securemessenger.di.AcceptanceEntryPoint
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class SyncFallbackTest {
    @Test
    fun real_sync_fallback() = runTest {
        withContext(Dispatchers.IO) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val args = InstrumentationRegistry.getArguments()
            val mode = args.getString("mode") ?: error("mode is required")
            val email = args.getString("email") ?: error("email is required")
            val password = args.getString("password") ?: error("password is required")
            val peerUsername = args.getString("peer_username") ?: error("peer_username is required")
            val expected = args.getString("expect").orEmpty()
            val entry = EntryPointAccessors.fromApplication(context, AcceptanceEntryPoint::class.java)
            if (entry.accounts().restore() == null) entry.accounts().login(email, password)
            when (mode) {
                "prepare" -> {
                    entry.messages().sync()
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                }
                "wss_event" -> {
                    val readyMarker = File(context.filesDir, "wss-ready")
                    readyMarker.delete()
                    val received = CompletableDeferred<Unit>()
                    val connection = entry.accounts().withValidAccessToken { token ->
                        entry.serverEvents().connectWithFailover(token) { received.complete(Unit) }
                    }
                    try {
                        connection.awaitOpen()
                        readyMarker.writeText("ready")
                        withTimeout(30_000) { received.await() }
                    } finally {
                        connection.close()
                        readyMarker.delete()
                    }
                }
                "send_direct" -> {
                    try {
                        val peer = entry.accounts().searchByUsername(peerUsername)
                        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stage", "peer_resolved") })
                        entry.messages().sendText(peer, expected)
                        InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("stage", "message_sent") })
                        val status = entry.messages().observeMessages(peer.accountId).first()
                            .single { it.body == expected }.status
                        assertTrue(status == "SENT" || status == "DELIVERED" || status == "READ")
                    } catch (error: Exception) {
                        val stats = entry.api().endpointStats()
                        InstrumentationRegistry.getInstrumentation().sendStatus(
                            0,
                            android.os.Bundle().apply { putString("endpoint_debug", stats.toString()) },
                        )
                        throw error
                    }
                }
                "prepare_pending_media" -> {
                    val peer = entry.accounts().searchByUsername(peerUsername)
                    val file = File(args.getString("media_path") ?: error("media_path is required"))
                    entry.media().prepare(
                        { file.inputStream() },
                        file.name,
                        "text/plain",
                        file.length(),
                        destinationChatId = "direct:${peer.accountId}",
                        recipientUsername = peer.username,
                        caption = expected,
                    )
                    assertEquals(1, entry.database().mediaUploads().pending().count { it.caption == expected })
                }
                "verify_pending_media" -> {
                    val peer = entry.accounts().searchByUsername(peerUsername)
                    withTimeout(30_000) {
                        entry.messages().observeMessages(peer.accountId)
                            .filter { chat -> chat.count { it.body == expected } == 1 }
                            .first()
                    }
                    assertTrue(entry.database().mediaUploads().all().single { it.caption == expected }.dispatched)
                    assertEquals(0, entry.pendingMedia().resumePending().dispatched)
                }
                "expect_direct_media" -> {
                    entry.messages().sync()
                    val peer = entry.accounts().searchByUsername(peerUsername)
                    val message = entry.messages().observeMessages(peer.accountId).first().last { it.body == expected }
                    val descriptor = kotlinx.serialization.json.Json.decodeFromString(
                        dev.securemessenger.data.e2ee.MediaDescriptor.serializer(),
                        requireNotNull(message.mediaJson),
                    )
                    assertEquals(expected, entry.media().readBytes(descriptor).decodeToString())
                }
                "offline_notification" -> {
                    val job = requireNotNull(context.getSystemService(JobScheduler::class.java).getPendingJob(SyncJobService.JOB_ID))
                    assertTrue(job.isPeriodic)
                    assertTrue(job.isPersisted)
                    assertEquals(SyncJobService.SYNC_INTERVAL_MILLIS, job.intervalMillis)
                    assertNotNull(job.requiredNetwork)
                    val peer = entry.accounts().searchByUsername(peerUsername)
                    withTimeout(45_000) {
                        entry.messages().observeMessages(peer.accountId)
                            .filter { chat -> chat.any { it.body == expected } }
                            .first()
                    }
                    val notification = waitForMessageNotification(context)
                    val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
                    val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
                    assertEquals("Новые зашифрованные сообщения", title)
                    assertFalse(expected in title || expected in body)
                    assertFalse(peerUsername in title || peerUsername in body)
                    MessageNotifier.clearMessages(context)
                }
                else -> error("Unknown mode $mode")
            }
        }
    }

    private suspend fun waitForMessageNotification(context: Context): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        return withTimeout(30_000) {
            var found: Notification? = null
            while (found == null) {
                found = manager.activeNotifications.firstOrNull {
                    it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Новые зашифрованные сообщения"
                }?.notification
                if (found == null) delay(100)
            }
            requireNotNull(found)
        }
    }
}
