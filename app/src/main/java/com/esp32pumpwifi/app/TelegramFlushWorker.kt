package com.esp32pumpwifi.app

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.IOException

class TelegramFlushWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "TELEGRAM_FLUSH"
    }

    override suspend fun doWork(): Result {
        val snapshot = TelegramAlertQueue.snapshot(applicationContext)
        if (snapshot.isEmpty()) {
            return Result.success()
        }

        val sentIds = LinkedHashSet<String>()
        var failed = false

        for (alert in snapshot) {
            if (!TelegramSender.isConfigured(applicationContext, alert.espId)) {
                Log.w(TAG, "Telegram non configuré pour espId=${alert.espId}, alerte supprimée")
                sentIds.add(alert.id)
                continue
            }

            val success = try {
                TelegramSender.sendAlertBlocking(applicationContext, alert)
            } catch (e: IOException) {
                // Réseau indisponible/instable : on réessaiera plus tard.
                return Result.retry()
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

        // Si de nouvelles alertes sont arrivées pendant le flush, on reprogramme un passage.
        if (TelegramAlertQueue.snapshot(applicationContext).isNotEmpty()) {
            TelegramAlertQueue.scheduleFlush(applicationContext)
        }

        return Result.success()
    }
}
