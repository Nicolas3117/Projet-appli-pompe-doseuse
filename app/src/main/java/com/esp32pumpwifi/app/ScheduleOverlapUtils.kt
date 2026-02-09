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
     * Hit inter-pompes (uniquement schedules enabled=true) permettant de décaler une dose candidate.
     * startMs/endMs sont déjà élargis avec l'offset anti-interférence.
     */
    data class InterferenceHit(
        val pumpNumber: Int,
        val startMs: Long,
        val endMs: Long
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

    /**
     * NOUVEAU (utilisé uniquement pour l'aide à la programmation):
     *
     * Retourne la première collision inter-pompes avec des schedules actifs (enabled=true),
     * en élargissant les fenêtres des AUTRES pompes avec offsetMs (anti-interférence chimique).
     *
     * - Exclut la pompe candidate.
     * - Exclut les schedules désactivés.
     * - Ne modifie rien au comportement manuel (popup warning) car cette fonction n'est appelée
     *   que dans le flux helper.
     *
     * Stratégie: on renvoie le hit dont endMs est le plus petit (collision "la plus proche"),
     * ce qui permet un décalage efficace: candidateStart = hit.endMs + 1.
     */
    fun findFirstActiveCrossPumpHit(
        context: Context,
        espId: Long,
        candidatePumpNumber: Int,
        candidateWindow: ScheduleWindow,
        offsetMs: Long,
        // Permet d'injecter une map déjà en mémoire (ex: pendant addSchedulesFromHelper),
        // sinon on retombe sur SharedPreferences comme findOverlaps().
        allSchedulesByPump: Map<Int, List<PumpSchedule>>? = null
    ): InterferenceHit? {
        if (offsetMs <= 0L) return null

        val prefs = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val schedulesPrefs = context.getSharedPreferences("schedules", Context.MODE_PRIVATE)

        fun loadPumpList(pump: Int): List<PumpSchedule> {
            // Si une map est fournie, on l'utilise (important pour inclure les ajouts en cours côté helper)
            allSchedulesByPump?.get(pump)?.let { return it }
            val json = schedulesPrefs.getString("esp_${espId}_pump$pump", null) ?: return emptyList()
            return PumpScheduleJson.fromJson(json)
        }

        var bestHit: InterferenceHit? = null

        for (pump in 1..4) {
            if (pump == candidatePumpNumber) continue

            val flow = prefs.getFloat("esp_${espId}_pump${pump}_flow", 0f)
            if (flow <= 0f) continue

            val list = loadPumpList(pump)
            for (schedule in list) {
                if (!schedule.enabled) continue

                val w = scheduleWindow(schedule.time, schedule.quantityTenth, flow) ?: continue

                val otherStart = (w.startMs - offsetMs).coerceAtLeast(0L)
                val otherEnd = (w.endMs + offsetMs).coerceAtMost(DAY_MS)

                val overlaps =
                    candidateWindow.startMs < otherEnd && candidateWindow.endMs > otherStart
                if (!overlaps) continue

                val hit = InterferenceHit(
                    pumpNumber = pump,
                    startMs = otherStart,
                    endMs = otherEnd
                )

                bestHit = when (bestHit) {
                    null -> hit
                    else -> if (hit.endMs < bestHit!!.endMs) hit else bestHit
                }
            }
        }

        return bestHit
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
