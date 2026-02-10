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
            return DoseValidationResult(isValid = false, reason = DoseValidationReason.INVALID_INTERVAL)
        }
        if (newInterval.endMs >= DAY_MS) {
            return DoseValidationResult(
                isValid = false,
                reason = DoseValidationReason.OVERFLOW_MIDNIGHT,
                overflowEndMs = newInterval.endMs
            )
        }

        val antiGapMs = antiMin.coerceAtLeast(0).toLong() * MINUTE_MS
        var nextAllowedStartMs: Long? = null

        for (it in existing) {
            val overlaps = newInterval.startMs < it.endMs && newInterval.endMs > it.startMs
            if (it.pump == newInterval.pump && overlaps) {
                val candidate = it.endMs
                nextAllowedStartMs = maxOf(nextAllowedStartMs ?: candidate, candidate)
                return DoseValidationResult(
                    isValid = false,
                    reason = DoseValidationReason.OVERLAP_SAME_PUMP,
                    conflictPump = it.pump,
                    conflictStartMs = it.startMs,
                    conflictEndMs = it.endMs,
                    nextAllowedStartMs = nextAllowedStartMs
                )
            }

            if (antiGapMs <= 0L || it.pump == newInterval.pump) continue

            val validBefore = newInterval.endMs + antiGapMs <= it.startMs
            val validAfter = newInterval.startMs >= it.endMs + antiGapMs
            if (!validBefore && !validAfter) {
                val candidate = it.endMs + antiGapMs
                nextAllowedStartMs = maxOf(nextAllowedStartMs ?: candidate, candidate)
                return DoseValidationResult(
                    isValid = false,
                    reason = DoseValidationReason.ANTI_INTERFERENCE_GAP,
                    conflictPump = it.pump,
                    conflictStartMs = it.startMs,
                    conflictEndMs = it.endMs,
                    nextAllowedStartMs = nextAllowedStartMs
                )
            }
        }

        return DoseValidationResult(isValid = true)
    }
}
