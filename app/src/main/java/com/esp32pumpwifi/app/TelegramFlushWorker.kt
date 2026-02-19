package com.esp32pumpwifi.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException

class TelegramFlushWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val snapshot = TelegramAlertQueue.snapshot(applicationContext)
        if (snapshot.isEmpty()) {
            return Result.success()
        }

        val sentIds = LinkedHashSet<String>()
        var failed = false
        var networkFailure = false

        for (alert in snapshot) {
            if (!TelegramSender.isConfigured(applicationContext, alert.espId)) {
                sentIds.add(alert.id)
                continue
            }

            val success = try {
                TelegramSender.sendAlertBlocking(applicationContext, alert)
            } catch (_: IOException) {
                // Réseau indisponible/instable : on réessaiera plus tard.
                networkFailure = true
                break
            }

            if (success) {
                sentIds.add(alert.id)
            } else {
                failed = true
                break
            }
        }

        if (sentIds.isNotEmpty()) {
            TelegramAlertQueue.removeByIds(applicationContext, sentIds)
        }

        if (failed) {
            // Erreur HTTP / réponse Telegram ok=false : on s'arrête et on réessaiera plus tard.
            return Result.retry()
        }

        if (networkFailure) {
            return Result.retry()
        }

        // Si de nouvelles alertes sont arrivées pendant le flush, on reprogramme un passage.
        if (TelegramAlertQueue.snapshot(applicationContext).isNotEmpty()) {
            TelegramAlertQueue.scheduleFlush(applicationContext)
        }

        return Result.success()
    }
}
