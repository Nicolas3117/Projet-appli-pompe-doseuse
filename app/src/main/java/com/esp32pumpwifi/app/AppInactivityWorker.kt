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
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppInactivityWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val PREFS_NAME = "prefs"
        private const val KEY_LAST_APP_OPEN_MS = "last_app_open_ms"

        private const val CHANNEL_ID = "app_inactivity_reminders"
        private const val CHANNEL_NAME = "Rappels d'ouverture"

        private const val NOTIF_BASE = 70123

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

        val now = System.currentTimeMillis()
        val todayKey = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

        val modules = Esp32Manager.getAll(context)
        for (module in modules) {
            val moduleId = module.id
            val moduleLastOpenKey = "esp_${moduleId}_last_open_ms"
            val moduleLastSentDayKey = "esp_${moduleId}_inactivity_last_sent_day"

            // Lecture module -> fallback global (migration douce)
            var lastOpen = prefs.getLong(moduleLastOpenKey, 0L)
            if (lastOpen == 0L) {
                lastOpen = prefs.getLong(KEY_LAST_APP_OPEN_MS, 0L)
                if (lastOpen != 0L) {
                    prefs.edit().putLong(moduleLastOpenKey, lastOpen).apply()
                }
            }

            if (lastOpen == 0L) continue

            val lastOpenDate = Instant.ofEpochMilli(lastOpen)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
            val todayDate = LocalDate.parse(todayKey)
            val days = ChronoUnit.DAYS.between(lastOpenDate, todayDate).toInt()

            if (days < MIN_INACTIVITY_DAYS) continue
            if (days > MAX_INACTIVITY_DAYS) continue

            // Anti-spam : 1 notif / jour / module
            if (prefs.getString(moduleLastSentDayKey, "") == todayKey) continue

            val baseMessage =
                if (days in 25..29) {
                    "Appli non ouverte depuis $days jours.\n" +
                            "Ouvrez « Contrôle des pompes » du module concerné pour actualiser le suivi."
                } else {
                    // days = 30..32
                    "Attention : appli non ouverte depuis plus de 30 jours.\n" +
                            "Ouvrez « Contrôle des pompes » du module concerné et refaites les niveaux des réservoirs."
                }

            val moduleMessage = "« ${module.displayName} » : $baseMessage"

            showNotification(context, moduleId, moduleMessage)

            if (TelegramSender.isConfigured(context, moduleId)) {
                // ✅ Envoi déterministe dans le cycle du worker + queue si échec
                sendTelegramForModule(context, moduleId, moduleMessage, todayKey, now)
            }

            prefs.edit().putString(moduleLastSentDayKey, todayKey).apply()
        }

        return Result.success()
    }

    private suspend fun sendTelegramForModule(
        context: Context,
        moduleId: Long,
        message: String,
        todayKey: String,
        timestamp: Long
    ) {
        val alert = TelegramAlert(
            // ✅ Stable 1/jour/module (évite doublons si plusieurs runs/queue)
            id = "INACTIVITY:$moduleId:$todayKey",
            espId = moduleId,
            pumpNum = -1,
            type = "INACTIVITY",
            message = message,
            timestamp = timestamp
        )

        try {
            val sent = withContext(Dispatchers.IO) {
                TelegramSender.sendAlertBlocking(context, alert)
            }
            if (!sent) {
                TelegramAlertQueue.enqueue(context, alert)
            }
        } catch (_: Exception) {
            TelegramAlertQueue.enqueue(context, alert)
        }
    }

    private fun showNotification(context: Context, moduleId: Long, message: String) {
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

        val notifId = NOTIF_BASE + (moduleId % 10000).toInt()
        NotificationManagerCompat.from(context).notify(notifId, notification)
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
