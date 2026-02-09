package com.esp32pumpwifi.app

import android.content.Context
import kotlin.math.roundToInt

object ScheduleOverlapUtils {

    private const val DAY_MS: Long = 24L * 60L * 60L * 1000L

    data class ScheduleWindow(val startMs: Long, val endMs: Long)

    data class OverlapResult(
        val samePumpConflict: Boolean,
        val overlappingPumpNames: Set<String>
    )

    /**
     * Étend une fenêtre de distribution avec une marge ±antiOverlapMinutes (en minutes),
     * et borne le résultat dans [0..24h].
     *
     * IMPORTANT: cette fonction est nouvelle et n'impacte pas le comportement existant
     * tant qu'elle n'est pas utilisée par les appelants.
     */
    fun expandWindow(window: ScheduleWindow, antiOverlapMinutes: Int): ScheduleWindow {
        val marginMs = antiOverlapMinutes.coerceAtLeast(0) * 60_000L
        val start = (window.startMs - marginMs).coerceAtLeast(0L)
        val end = (window.endMs + marginMs).coerceAtMost(DAY_MS)
        return ScheduleWindow(startMs = start, endMs = end)
    }

    fun parseTimeOrNull(time: String): Pair<Int, Int>? {
        val t = time.trim()
        if (!t.matches(Regex("""\d{2}:\d{2}"""))) return null
        val parts = t.split(":")
        if (parts.size != 2) return null
        val hh = parts[0].toIntOrNull() ?: return null
        val mm = parts[1].toIntOrNull() ?: return null
        if (hh !in 0..23) return null
        if (mm !in 0..59) return null
        return hh to mm
    }

    fun timeToStartMs(time: String): Long? {
        val parsed = parseTimeOrNull(time) ?: return null
        val (h, m) = parsed
        return (h * 3600L + m * 60L) * 1000L
    }

    fun durationMsFromQuantity(quantityTenth: Int, flow: Float): Int? {
        if (flow <= 0f) return null
        val quantityMl = QuantityInputUtils.quantityMl(quantityTenth)
        val durationMs = (quantityMl / flow * 1000f).roundToInt()
        if (durationMs < ManualDoseActivity.MIN_PUMP_DURATION_MS) return null
        if (durationMs > ManualDoseActivity.MAX_PUMP_DURATION_MS) return null
        return durationMs
    }

    fun scheduleWindow(time: String, quantityTenth: Int, flow: Float): ScheduleWindow? {
        val startMs = timeToStartMs(time) ?: return null
        val durationMs = durationMsFromQuantity(quantityTenth, flow) ?: return null
        return ScheduleWindow(startMs, startMs + durationMs.toLong())
    }

    fun findOverlaps(
        context: Context,
        espId: Long,
        pumpNumber: Int,
        candidateWindow: ScheduleWindow,
        samePumpSchedules: List<PumpSchedule>? = null,
        ignoreSamePumpPredicate: ((index: Int, schedule: PumpSchedule) -> Boolean)? = null
    ): OverlapResult {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val schedulesPrefs = context.getSharedPreferences("schedules", Context.MODE_PRIVATE)
        var samePumpConflict = false
        val overlappingPumpNames = mutableSetOf<String>()

        fun checkSchedules(
            pump: Int,
            list: List<PumpSchedule>,
            applyIgnorePredicate: Boolean
        ) {
            val flow = prefs.getFloat("esp_${espId}_pump${pump}_flow", 0f)
            if (flow <= 0f) return

            for ((index, schedule) in list.withIndex()) {
                if (!schedule.enabled) continue
                if (applyIgnorePredicate && ignoreSamePumpPredicate?.invoke(index, schedule) == true) {
                    continue
                }

                val window = scheduleWindow(schedule.time, schedule.quantityTenth, flow) ?: continue
                if (candidateWindow.startMs < window.endMs && candidateWindow.endMs > window.startMs) {
                    if (pump == pumpNumber) {
                        samePumpConflict = true
                        return
                    }
                    overlappingPumpNames.add(getPumpName(context, espId, pump))
                }
            }
        }

        val samePumpList = samePumpSchedules ?: run {
            val json =
                schedulesPrefs.getString("esp_${espId}_pump$pumpNumber", null) ?: return@run emptyList()
            PumpScheduleJson.fromJson(json)
        }

        checkSchedules(pumpNumber, samePumpList, true)
        if (samePumpConflict) {
            return OverlapResult(samePumpConflict = true, overlappingPumpNames = emptySet())
        }

        for (pump in 1..4) {
            if (pump == pumpNumber) continue
            val json = schedulesPrefs.getString("esp_${espId}_pump$pump", null) ?: continue
            val list: List<PumpSchedule> = PumpScheduleJson.fromJson(json)
            checkSchedules(pump, list, false)
        }

        return OverlapResult(
            samePumpConflict = samePumpConflict,
            overlappingPumpNames = overlappingPumpNames
        )
    }

    private fun getPumpName(context: Context, espId: Long, pump: Int): String {
        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        return prefs.getString(
            "esp_${espId}_pump${pump}_name",
            "Pompe $pump"
        ) ?: "Pompe $pump"
    }
}
