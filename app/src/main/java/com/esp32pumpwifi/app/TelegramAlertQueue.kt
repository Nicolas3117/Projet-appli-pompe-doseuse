package com.esp32pumpwifi.app

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object TelegramAlertQueue {

    private const val TAG = "TELEGRAM_QUEUE"
    private const val PREFS_NAME = "telegram_alert_queue"
    private const val KEY_QUEUE_JSON = "queue_json"
    private const val MAX_QUEUE_SIZE = 200

    internal const val UNIQUE_WORK_NAME = "telegram_flush_queue"

    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<TelegramAlert>>() {}.type
    private val lock = Any()

    fun enqueue(context: Context, alert: TelegramAlert) {
        val appContext = context.applicationContext
        if (!TelegramSender.isConfigured(appContext, alert.espId)) {
            Log.w(TAG, "Telegram non configuré pour espId=${alert.espId}, alerte ignorée")
            return
        }

        val added = synchronized(lock) {
            val prefs = prefs(appContext)
            val queue = readQueueLocked(prefs)

            val alreadyQueued = queue.any { it.id == alert.id }
            if (!alreadyQueued) {
                queue.add(alert)
                trimToMaxSize(queue)
                writeQueueLocked(prefs, queue)
                true
            } else {
                false
            }
        }

        if (!added) {
            Log.d(TAG, "Alerte déjà en file: ${alert.id}")
        }

        scheduleFlush(appContext)
    }

    fun trySendNowOrQueue(context: Context, alert: TelegramAlert) {
        val appContext = context.applicationContext
        if (!TelegramSender.isConfigured(appContext, alert.espId)) {
            Log.w(TAG, "Telegram non configuré pour espId=${alert.espId}, alerte ignorée")
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val sentNow = TelegramSender.sendAlertBlocking(appContext, alert)
            if (sentNow) {
                removeById(appContext, alert.id)
                // Même en cas de succès immédiat, on tente de vider un éventuel backlog.
                scheduleFlush(appContext)
            } else {
                enqueue(appContext, alert)
            }
        }
    }

    internal fun snapshot(context: Context): List<TelegramAlert> = synchronized(lock) {
        readQueueLocked(prefs(context.applicationContext)).toList()
    }

    internal fun removeByIds(context: Context, ids: Set<String>) {
        if (ids.isEmpty()) return

        val appContext = context.applicationContext
        synchronized(lock) {
            val prefs = prefs(appContext)
            val queue = readQueueLocked(prefs)
            val updated = queue.filterNot { it.id in ids }
            writeQueueLocked(prefs, updated)
        }
    }

    internal fun removeById(context: Context, id: String) {
        removeByIds(context, setOf(id))
    }

    internal fun scheduleFlush(context: Context) {
        val appContext = context.applicationContext
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<TelegramFlushWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS
            )
            .build()

        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun readQueueLocked(prefs: android.content.SharedPreferences): MutableList<TelegramAlert> {
        val json = prefs.getString(KEY_QUEUE_JSON, null) ?: return mutableListOf()
        return try {
            gson.fromJson<MutableList<TelegramAlert>>(json, listType) ?: mutableListOf()
        } catch (e: Exception) {
            Log.e(TAG, "Impossible de lire la file Telegram, réinitialisation")
            prefs.edit().remove(KEY_QUEUE_JSON).apply()
            mutableListOf()
        }
    }

    private fun writeQueueLocked(
        prefs: android.content.SharedPreferences,
        queue: List<TelegramAlert>
    ) {
        prefs.edit()
            .putString(KEY_QUEUE_JSON, gson.toJson(queue))
            .apply()
    }

    private fun trimToMaxSize(queue: MutableList<TelegramAlert>) {
        if (queue.size <= MAX_QUEUE_SIZE) return

        val toRemove = queue.size - MAX_QUEUE_SIZE
        repeat(toRemove) {
            if (queue.isNotEmpty()) queue.removeAt(0)
        }
    }
}
