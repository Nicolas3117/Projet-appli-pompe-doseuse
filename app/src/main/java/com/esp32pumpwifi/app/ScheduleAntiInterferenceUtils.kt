package com.esp32pumpwifi.app

import kotlin.math.ceil

object ScheduleAntiInterferenceUtils {

    private const val MS_PER_MINUTE = 60_000L

    fun computeMaxDoses(windowMs: Long, antiOverlapMinutes: Int): Int {
        if (antiOverlapMinutes <= 0) return Int.MAX_VALUE
        val safeWindowMs = windowMs.coerceAtLeast(0L)
        val minStepMs = antiOverlapMinutes * MS_PER_MINUTE
        return (safeWindowMs / minStepMs).toInt() + 1
    }

    fun minAntiMinutesRequired(windowMs: Long, doseCount: Int): Int {
        if (doseCount <= 1) return 0
        val safeWindowMs = windowMs.coerceAtLeast(0L)
        val minAntiMinutes = safeWindowMs.toDouble() / (doseCount - 1).toDouble() / MS_PER_MINUTE.toDouble()
        return ceil(minAntiMinutes).toInt().coerceAtLeast(0)
    }
}

