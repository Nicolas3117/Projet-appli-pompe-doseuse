package com.esp32pumpwifi.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
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

        private const val NOTIF_ID = 70123
        private const val LOG_TAG = "INACTIVITY"

        // ✅ Fenêtre voulue : notif de J+25 à J+32 inclus
        private const val MIN_INACTIVITY_DAYS = 25
        private const val MAX_INACTIVITY_DAYS = 32
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Crée le channel dès que le worker tourne (même si on ne notifie pas),
        // pour qu'il apparaisse dans Paramètres > Notifications.
        ensureChannel(context)

        val lastOpen = prefs.getLong(KEY_LAST_APP_OPEN_MS, 0L)
        if (lastOpen == 0L) {
            Log.w(LOG_TAG, "SKIP lastOpen=0 (never opened yet?)")
            return Result.success()
        }

        val now = System.currentTimeMillis()
        val days = ((now - lastOpen) / DAY_MS).toInt()

        Log.w(LOG_TAG, "RUN days=$days lastOpen=$lastOpen now=$now")

        if (days < MIN_INACTIVITY_DAYS) {
            Log.w(LOG_TAG, "SKIP days<${MIN_INACTIVITY_DAYS} ($days)")
            return Result.success()
        }

        if (days > MAX_INACTIVITY_DAYS) {
            Log.w(LOG_TAG, "SKIP days>${MAX_INACTIVITY_DAYS} ($days)")
            return Result.success()
        }

        val todayKey = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

        if (prefs.getString(KEY_LAST_SENT_DAY, "") == todayKey) {
            Log.w(LOG_TAG, "SKIP already sent today ($todayKey)")
            return Result.success()
        }

        val message =
            if (days in 25..29) {
                "Attention : appli non ouverte depuis $days jours. Ouvrez l’application puis allez dans « Contrôle des pompes » de chaque module pour vérifier et actualiser le suivi des réservoirs."
            } else {
                // days = 30..32
                "Attention : appli non ouverte depuis plus de 30 jours. Ouvrez « Contrôle des pompes » de vos modules et refaites les niveaux des réservoirs."
            }

        Log.w(LOG_TAG, "SENDING notification days=$days")

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

        Log.w(LOG_TAG, "DONE sent (todayKey=$todayKey)")
        return Result.success()
    }

    private fun showNotification(context: Context, message: String) {
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

        NotificationManagerCompat.from(context).notify(NOTIF_ID, notification)
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

        Log.w(LOG_TAG, "Channel created: $CHANNEL_ID")
    }
}
