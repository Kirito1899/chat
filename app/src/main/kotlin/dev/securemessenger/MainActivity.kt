package dev.securemessenger

import android.os.Bundle
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.securemessenger.data.sync.AppVisibility
import dev.securemessenger.data.sync.MessageNotifier
import dev.securemessenger.data.sync.SyncService
import dev.securemessenger.ui.AdaptiveApp
import dev.securemessenger.ui.MessengerTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.DEBUG) {
            window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        enableEdgeToEdge()
        setContent {
            MessengerTheme {
                AdaptiveApp()
            }
        }
        SyncService.start(this)
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
}
