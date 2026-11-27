package dev.securemessenger.data.sync

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.securemessenger.MainActivity
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@RunWith(AndroidJUnit4::class)
class MessageNotifierTest {
    @Test
    fun notification_deep_link_contains_only_local_conversation_key() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS"
        ).use { }
        val key = UUID.randomUUID().toString()
        instrumentation.waitForIdleSync()
        AppVisibility.foreground = false
        MessageNotifier.showMessages(context, 1, key)
        val manager = context.getSystemService(NotificationManager::class.java)
        var found: Notification? = null
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (found == null && SystemClock.elapsedRealtime() < deadline) {
            found = manager.activeNotifications.firstOrNull {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Новые зашифрованные сообщения"
            }?.notification
            if (found == null) Thread.sleep(50)
        }
        val notification = assertNotNull(found)
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertFalse(key in title || key in body)

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        notification.contentIntent.send()
        instrumentation.waitForIdleSync()

        scenario.onActivity { activity ->
            assertEquals(MainActivity.ACTION_OPEN_CONVERSATION, activity.intent.action)
            assertEquals(key, activity.intent.getStringExtra(MainActivity.EXTRA_NOTIFICATION_KEY))
        }
        MessageNotifier.showMessages(context, 1, UUID.randomUUID().toString())
        SystemClock.sleep(200)
        assertFalse(manager.activeNotifications.any {
            it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() == "Новые зашифрованные сообщения"
        })
        MessageNotifier.clearMessages(context)
        scenario.onActivity { it.finish() }
        instrumentation.waitForIdleSync()
    }
}
