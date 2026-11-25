package dev.securemessenger

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.securemessenger.data.sync.SyncJobService

@HiltAndroidApp
class MessengerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncJobService.schedule(this)
    }
}
