package com.esp32pumpwifi.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class AppInactivityWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        InactivityChecker.run(applicationContext)
        return Result.success()
    }
}
