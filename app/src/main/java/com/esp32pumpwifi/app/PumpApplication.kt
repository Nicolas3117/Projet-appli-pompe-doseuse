package com.esp32pumpwifi.app

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class PumpApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        TankNotification.ensureChannels(this)

        // 🔁 Worker périodique toutes les 15 minutes
        val work =
            PeriodicWorkRequestBuilder<TankRecalcWorker>(
                15, TimeUnit.MINUTES
            )
                .addTag("tank_recalc")
                .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork(
                "tank_recalc",
                ExistingPeriodicWorkPolicy.KEEP,
                work
            )
    }
}
