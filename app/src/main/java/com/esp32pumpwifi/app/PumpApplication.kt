package com.esp32pumpwifi.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class PumpApplication : Application() {

    companion object {
        // Doit matcher AppInactivityWorker.CHANNEL_ID
        private const val INACTIVITY_CHANNEL_ID = "app_inactivity_reminders"
        private const val INACTIVITY_CHANNEL_NAME = "Rappels d'ouverture"
    }

    override fun onCreate() {
        super.onCreate()

        // 🔔 Channels de notifications Android (API 26+)
        TankNotification.ensureChannels(this)

        // ✅ Ajout minimal : créer aussi le channel "inactivité" au démarrage,
        // pour qu'il apparaisse dans Paramètres > Notifications (même si le worker n'a pas encore notifié).
        ensureInactivityChannel()

        // ✅ Garantit le flush de la queue Telegram dès que le réseau est dispo
        // (utile après reboot / si l'app n'est pas relancée au bon moment)
        TelegramAlertQueue.scheduleFlush(this)

        // 🔁 Worker périodique toutes les 15 minutes
        val work =
            PeriodicWorkRequestBuilder<TankRecalcWorker>(
                15, TimeUnit.MINUTES
            )
                .addTag("tank_recalc")
                .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                "tank_recalc",
                ExistingPeriodicWorkPolicy.KEEP,
                work
            )

        val nowZoned =
            Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
        var target = nowZoned.withHour(12).withMinute(0).withSecond(0).withNano(0)
        if (!nowZoned.isBefore(target)) {
            target = target.plusDays(1)
        }

        val delayMs = Duration.between(nowZoned, target).toMillis()

        val inactivityWork =
            PeriodicWorkRequestBuilder<AppInactivityWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag("app_inactivity")
                .build()

        WorkManager.getInstance(applicationContext)
            .enqueueUniquePeriodicWork(
                "app_inactivity",
                ExistingPeriodicWorkPolicy.KEEP,
                inactivityWork
            )
    }

    private fun ensureInactivityChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(INACTIVITY_CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            INACTIVITY_CHANNEL_ID,
            INACTIVITY_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT
        )
        manager.createNotificationChannel(channel)
    }
}
