package dev.securemessenger.data.sync

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.securemessenger.MainActivity
import dev.securemessenger.R

object MessageNotifier {
    private const val SYNC_CHANNEL = "protected_sync"
    private const val MESSAGE_CHANNEL = "protected_messages"
    private const val SYNC_ID = 7001
    private const val MESSAGE_ID = 7002

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(SYNC_CHANNEL, "Фоновая синхронизация", NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(MESSAGE_CHANNEL, "Зашифрованные сообщения", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun foregroundNotification(context: Context): Notification {
        createChannels(context)
        return NotificationCompat.Builder(context, SYNC_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Синхронизация сообщений")
            .setContentText("Защищённое соединение")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun showMessages(context: Context, count: Int) {
        if (AppVisibility.foreground || count <= 0) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannels(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, MESSAGE_CHANNEL)
            .setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("Новые зашифрованные сообщения")
            .setContentText("Получено: $count. Откройте приложение для просмотра")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(MESSAGE_ID, notification)
    }

    fun clearMessages(context: Context) {
        NotificationManagerCompat.from(context).cancel(MESSAGE_ID)
    }
}

object AppVisibility {
    @Volatile
    var foreground: Boolean = false
}
