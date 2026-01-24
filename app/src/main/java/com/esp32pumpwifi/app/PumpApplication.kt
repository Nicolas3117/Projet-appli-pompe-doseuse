package com.esp32pumpwifi.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
    }
}
