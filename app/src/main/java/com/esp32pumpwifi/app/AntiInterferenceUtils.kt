package com.esp32pumpwifi.app

import android.content.Context

private const val PREFS_NAME = "prefs"
private const val MS_PER_DAY = 86_400_000L

/**
 * Returns the anti-interference value (minutes) configured at module level.
 */
fun getAntiInterferenceMinutes(context: Context, moduleId: Long): Int {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt("esp_${moduleId}_anti_overlap_minutes", 0)
        .coerceAtLeast(0)
}

/**
 * Formats the canonical anti-interference blocking message.
 */
fun antiInterferenceGapErrorMessage(
    antiMin: Int,
    blockingPumpName: String,
    nextAllowedMs: Long?
): String {
    val next = nextAllowedMs?.let { ScheduleAddMergeUtils.toTimeString(normalizeInDay(it)) } ?: "--:--"
    return formatAntiInterferenceGapErrorMessage(antiMin, blockingPumpName, next)
}

fun formatAntiInterferenceGapErrorMessage(
    antiMin: Int,
    blockingPumpName: String,
    nextAllowedTime: String
): String {
    return "Respectez au moins $antiMin min après la fin de la distribution de la pompe $blockingPumpName. Prochaine heure possible : $nextAllowedTime"
}

private fun normalizeInDay(valueMs: Long): Long {
    return (valueMs % MS_PER_DAY + MS_PER_DAY) % MS_PER_DAY
}
