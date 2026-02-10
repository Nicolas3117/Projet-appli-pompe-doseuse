package com.esp32pumpwifi.app

import kotlin.math.roundToLong

private const val DAY_MS: Long = 86_400_000L
private const val MINUTE_MS: Long = 60_000L

data class DoseInterval(
    val pump: Int,
    val startMs: Long,
    val endMs: Long
)

enum class DoseValidationReason {
    OVERLAP_SAME_PUMP,
    ANTI_INTERFERENCE_GAP,
    OVERFLOW_MIDNIGHT,
    INVALID_INTERVAL
}

data class DoseValidationResult(
    val isValid: Boolean,
    val reason: DoseValidationReason? = null,
    val conflictPump: Int? = null,
    val conflictStartMs: Long? = null,
    val conflictEndMs: Long? = null,
    val nextAllowedStartMs: Long? = null,
    val overflowEndMs: Long? = null
)

object DoseValidationUtils {

    fun computeDurationMs(volumeMl: Double, flowMlPerSec: Float): Long? {
        if (flowMlPerSec <= 0f || volumeMl <= 0.0) return null
        val durationMs = (volumeMl / flowMlPerSec.toDouble() * 1000.0).roundToLong()
        return if (durationMs > 0L) durationMs else null
    }

    fun buildIntervalsFromSchedules(
        schedulesByPump: Map<Int, List<PumpSchedule>>,
        flowByPump: Map<Int, Float>
    ): List<DoseInterval> {
        val intervals = mutableListOf<DoseInterval>()
        for (pump in 1..4) {
            val flow = flowByPump[pump] ?: 0f
            if (flow <= 0f) continue
            val list = schedulesByPump[pump].orEmpty()
            for (schedule in list) {
                if (!schedule.enabled) continue
                val startMs = ScheduleOverlapUtils.timeToStartMs(schedule.time) ?: continue
                val durationMs = computeDurationMs(schedule.quantityMl.toDouble(), flow) ?: continue
                val endMs = startMs + durationMs
                if (endMs >= DAY_MS) continue
                intervals.add(DoseInterval(pump = pump, startMs = startMs, endMs = endMs))
            }
        }
        return intervals.sortedBy { it.startMs }
    }

    fun validateNewInterval(
        newInterval: DoseInterval,
        existing: List<DoseInterval>,
        antiMin: Int
    ): DoseValidationResult {
        if (newInterval.startMs < 0L || newInterval.endMs <= newInterval.startMs) {
            android.util.Log.w("ANTI_INTERFERENCE", "invalid_interval candidate=$newInterval")
            return DoseValidationResult(isValid = false, reason = DoseValidationReason.INVALID_INTERVAL)
        }
        if (newInterval.endMs >= DAY_MS) {
            android.util.Log.w("ANTI_INTERFERENCE", "overflow candidate=$newInterval")
            return DoseValidationResult(
                isValid = false,
                reason = DoseValidationReason.OVERFLOW_MIDNIGHT,
                overflowEndMs = newInterval.endMs
            )
        }

        val antiGapMs = antiMin.coerceAtLeast(0).toLong() * MINUTE_MS

        for (interval in existing) {
            val overlaps = newInterval.startMs < interval.endMs && newInterval.endMs > interval.startMs
            if (interval.pump == newInterval.pump && overlaps) {
                android.util.Log.w(
                    "ANTI_INTERFERENCE",
                    "invalid reason=overlap_same_pump candidate=$newInterval conflict=$interval"
                )
                return DoseValidationResult(
                    isValid = false,
                    reason = DoseValidationReason.OVERLAP_SAME_PUMP,
                    conflictPump = interval.pump,
                    conflictStartMs = interval.startMs,
                    conflictEndMs = interval.endMs,
                    nextAllowedStartMs = interval.endMs
                )
            }
        }

        if (antiGapMs <= 0L) {
            android.util.Log.i("ANTI_INTERFERENCE", "valid antiMin=0 candidate=$newInterval")
            return DoseValidationResult(isValid = true)
        }

        var nextAllowedStartMs: Long? = null
        for (interval in existing) {
            if (interval.pump == newInterval.pump) continue

            val validBefore = newInterval.endMs + antiGapMs <= interval.startMs
            val validAfter = newInterval.startMs >= interval.endMs + antiGapMs
            if (!validBefore && !validAfter) {
                val candidateStart = interval.endMs + antiGapMs
                nextAllowedStartMs = maxOf(nextAllowedStartMs ?: candidateStart, candidateStart)
            }
        }

        if (nextAllowedStartMs != null) {
            android.util.Log.w(
                "ANTI_INTERFERENCE",
                "invalid reason=anti_gap candidate=$newInterval antiMin=$antiMin nextAllowedStartMs=$nextAllowedStartMs"
            )
            return DoseValidationResult(
                isValid = false,
                reason = DoseValidationReason.ANTI_INTERFERENCE_GAP,
                nextAllowedStartMs = nextAllowedStartMs
            )
        }

        android.util.Log.i("ANTI_INTERFERENCE", "valid candidate=$newInterval antiMin=$antiMin")
        return DoseValidationResult(isValid = true)
    }
}
