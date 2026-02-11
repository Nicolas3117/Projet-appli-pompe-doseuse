package com.esp32pumpwifi.app

import android.content.Context

private const val PREFS_NAME = "prefs"
private const val MS_PER_MINUTE = 60_000L

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
    return DoseErrorMessageFormatter.antiInterferenceGap(antiMin, blockingPumpName, nextAllowedMs)
}

fun ceilToMinute(valueMs: Long): Long {
    if (valueMs <= 0L) return 0L
    return ((valueMs + MS_PER_MINUTE - 1L) / MS_PER_MINUTE) * MS_PER_MINUTE
}

fun formatAntiInterferenceGapErrorMessage(
    antiMin: Int,
    blockingPumpName: String,
    nextAllowedTime: String
): String =
    "Anti-interférence chimique (${antiMin.coerceAtLeast(0)} min) : $blockingPumpName bloque ce créneau.\nProchaine heure possible : $nextAllowedTime."
