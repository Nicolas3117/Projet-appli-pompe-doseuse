package com.esp32pumpwifi.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class CriticalAlarmsBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        CriticalAlarmScheduler.rescheduleAfterBoot(context.applicationContext)
    }
}
