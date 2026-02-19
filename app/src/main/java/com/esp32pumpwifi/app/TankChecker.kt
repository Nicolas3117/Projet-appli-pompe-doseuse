package com.esp32pumpwifi.app

import android.content.Context

object TankChecker {

    fun run(context: Context) {
        val appContext = context.applicationContext
        val modules = Esp32Manager.getAll(appContext)

        for (module in modules) {
            TankScheduleHelper.recalculateFromLastTime(
                context = appContext,
                espId = module.id
            )
        }
    }
}
