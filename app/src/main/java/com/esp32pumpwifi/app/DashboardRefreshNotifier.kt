package com.esp32pumpwifi.app

import android.content.Context
import android.content.Intent

object DashboardRefreshNotifier {
    const val ACTION_PROGRAM_UPDATED = "com.esp32pumpwifi.app.ACTION_PROGRAM_UPDATED"
    const val EXTRA_MODULE_ID = "extra_module_id"

    fun notifyProgramUpdated(context: Context, moduleId: Long) {
        context.sendBroadcast(
            Intent(ACTION_PROGRAM_UPDATED).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_MODULE_ID, moduleId)
            }
        )
    }
}

