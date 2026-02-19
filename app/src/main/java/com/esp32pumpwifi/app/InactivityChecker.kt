package com.esp32pumpwifi.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object InactivityChecker {

    private const val PREFS_NAME = "prefs"
    private const val KEY_LAST_APP_OPEN_MS = "last_app_open_ms"

    private const val CHANNEL_ID = "app_inactivity_reminders"
    private const val CHANNEL_NAME = "Rappels d'ouverture"

    private const val NOTIF_BASE = 70123
    private const val MIN_INACTIVITY_DAYS = 25
    private const val MAX_INACTIVITY_DAYS = 32

    suspend fun run(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        ensureChannel(appContext)

        val now = System.currentTimeMillis()
        val todayKey = Instant.ofEpochMilli(now)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .toString()

        val modules = Esp32Manager.getAll(appContext)
        for (module in modules) {
            val moduleId = module.id

            // Pas de notif inactivité si aucune programmation active sur ce module
            if (!hasAnyActiveProgram(appContext, moduleId)) continue

            val moduleLastOpenKey = "esp_${moduleId}_last_open_ms"
            val moduleLastSentDayKey = "esp_${moduleId}_inactivity_last_sent_day"

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

            if (days < MIN_INACTIVITY_DAYS || days > MAX_INACTIVITY_DAYS) continue
            if (prefs.getString(moduleLastSentDayKey, "") == todayKey) continue

            val baseMessage =
                if (days in 25..29) {
                    "Appli non ouverte depuis $days jours.\n" +
                            "Ouvrez « Contrôle des pompes » du module concerné pour actualiser le suivi."
                } else {
                    "Attention : appli non ouverte depuis plus de 30 jours.\n" +
                            "Ouvrez « Contrôle des pompes » du module concerné et refaites les niveaux des réservoirs."
                }

            val moduleMessage = "« ${module.displayName} » : $baseMessage"

            showNotification(appContext, moduleId, moduleMessage)

            if (TelegramSender.isConfigured(appContext, moduleId)) {
                sendTelegramForModule(appContext, moduleId, moduleMessage, todayKey, now)
            }

            prefs.edit().putString(moduleLastSentDayKey, todayKey).apply()
        }
    }

    private fun hasAnyActiveProgram(context: Context, moduleId: Long): Boolean {
        for (pumpNum in 1..4) {
            val lines = ProgramStoreSynced.loadEncodedLines(context, moduleId, pumpNum)
            if (lines.any { isActiveProgramLine(it) }) return true
        }
        return false
    }

    private fun isActiveProgramLine(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.length == 12 && trimmed.all { it.isDigit() } && trimmed.first() == '1'
    }

    private suspend fun sendTelegramForModule(
        context: Context,
        moduleId: Long,
        message: String,
        todayKey: String,
        timestamp: Long
    ) {
        val alert = TelegramAlert(
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
            if (!sent) TelegramAlertQueue.enqueue(context, alert)
        } catch (_: Exception) {
            TelegramAlertQueue.enqueue(context, alert)
        }
    }

    private fun showNotification(context: Context, moduleId: Long, message: String) {
        // requestCode stable par module -> évite tout effet de bord entre modules
        val requestCode = (moduleId % 10000).toInt()

        val pendingIntent = PendingIntent.getActivity(
            context,
            requestCode,
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

    fun ensureChannel(context: Context) {
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
