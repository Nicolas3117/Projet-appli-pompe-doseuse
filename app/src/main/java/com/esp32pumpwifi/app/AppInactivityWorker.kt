package com.esp32pumpwifi.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.ZoneId

class AppInactivityWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val PREFS_NAME = "prefs"
        private const val KEY_LAST_APP_OPEN_MS = "last_app_open_ms"
        private const val KEY_LAST_SENT_DAY = "inactivity_last_sent_day"
        private const val DAY_MS = 86400000L

        private const val CHANNEL_ID = "app_inactivity_reminders"
        private const val CHANNEL_NAME = "Rappels d'ouverture"
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val lastOpen = prefs.getLong(KEY_LAST_APP_OPEN_MS, 0L)
        if (lastOpen == 0L) return Result.success()

        val now = System.currentTimeMillis()
        val days = ((now - lastOpen) / DAY_MS).toInt()

        if (days < 25) return Result.success()
        if (days > 31) return Result.success()

        val todayKey = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

        if (prefs.getString(KEY_LAST_SENT_DAY, "") == todayKey) {
            return Result.success()
        }

        val message =
            if (days in 25..29) {
                "Attention : appli non ouverte depuis $days jours. Ouvrez l’application puis allez dans « Contrôle des pompes » de chaque module pour vérifier et actualiser le suivi des réservoirs."
            } else {
                "Attention : appli non ouverte depuis plus de 30 jours. Ouvrez « Contrôle des pompes » de vos modules et refaites les niveaux des réservoirs."
            }

        showNotification(context, message)

        val modules = Esp32Manager.getAll(context)
        for (module in modules) {
            if (TelegramSender.isConfigured(context, module.id)) {
                TelegramSender.sendMessage(context, module.id, message)
            }
        }

        prefs.edit()
            .putString(KEY_LAST_SENT_DAY, todayKey)
            .apply()

        return Result.success()
    }

    private fun showNotification(context: Context, message: String) {
        ensureChannel(context)

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Application non ouverte")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context)
            .notify(70123, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }
}
