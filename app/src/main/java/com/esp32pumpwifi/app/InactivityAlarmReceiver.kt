package com.esp32pumpwifi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class InactivityAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            try {
                try {
                    withTimeout(25_000L) {
                        InactivityChecker.run(appContext)
                    }
                } catch (_: TimeoutCancellationException) {
                } catch (_: Exception) {
                } finally {
                    CriticalAlarmScheduler.scheduleInactivityAlarmDaily(appContext)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
