package com.esp32pumpwifi.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

class PumpApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // 🔔 Channels de notifications Android (API 26+)
        TankNotification.ensureChannels(this)

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

        val nowZoned = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZoneId.systemDefault())
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
}
