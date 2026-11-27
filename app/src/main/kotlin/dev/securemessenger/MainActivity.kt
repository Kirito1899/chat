package dev.securemessenger

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.AndroidEntryPoint
import dev.securemessenger.data.sync.AppVisibility
import dev.securemessenger.data.sync.MessageNotifier
import dev.securemessenger.data.sync.SyncService
import dev.securemessenger.ui.AdaptiveApp
import dev.securemessenger.ui.MessengerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val notificationKey = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationKey.value = intent.notificationConversationKey()
        if (BuildConfig.DEBUG) {
            window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        enableEdgeToEdge()
        setContent {
            MessengerTheme {
                AdaptiveApp(notificationKey.value, ::onNotificationHandled)
            }
        }
        SyncService.start(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationKey.value = intent.notificationConversationKey()
    }

    override fun onResume() {
        super.onResume()
        AppVisibility.foreground = true
        MessageNotifier.clearMessages(this)
    }

    override fun onPause() {
        AppVisibility.foreground = false
        super.onPause()
    }

    private fun onNotificationHandled() {
        notificationKey.value = null
        intent.removeExtra(EXTRA_NOTIFICATION_KEY)
        if (intent.action == ACTION_OPEN_CONVERSATION) intent.action = null
    }

    private fun Intent.notificationConversationKey(): String? =
        if (action == ACTION_OPEN_CONVERSATION) getStringExtra(EXTRA_NOTIFICATION_KEY) else null

    companion object {
        const val ACTION_OPEN_CONVERSATION = "dev.securemessenger.OPEN_CONVERSATION"
        const val EXTRA_NOTIFICATION_KEY = "notification_key"
    }
}
