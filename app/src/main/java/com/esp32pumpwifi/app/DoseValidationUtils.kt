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
    val conflictPumpNum: Int? = null,
    val conflictPump: Int? = null,
    val conflictStartMs: Long? = null,
    val conflictEndMs: Long? = null,
    val nextAllowedStartMs: Long? = null,
    val overflowEndMs: Long? = null
)

object DoseValidationUtils {

    /** Returns computed duration in milliseconds, or null when volume/flow is invalid. */
    fun computeDurationMs(volumeMl: Double, flowMlPerSec: Float): Long? {
        if (flowMlPerSec <= 0f || volumeMl <= 0.0) return null
        val durationMs = (volumeMl / flowMlPerSec.toDouble() * 1000.0).roundToLong()
        return if (durationMs > 0L) durationMs else null
    }

    /**
     * Builds all active intervals from schedules and calibrated flows, sorted by start time.
     * Invalid intervals (missing time/flow or midnight overflow) are ignored.
     */
    fun buildIntervalsFromSchedules(
        schedulesByPump: Map<Int, List<PumpSchedule>>,
        flowByPump: Map<Int, Float>
    ): List<DoseInterval> {
        val intervals = mutableListOf<DoseInterval>()
        for (pump in 1..4) {
            val flow = flowByPump[pump] ?: 0f
            if (flow <= 0f) continue
            for (schedule in schedulesByPump[pump].orEmpty()) {
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

    /**
     * Validates a candidate interval against invariant rules:
     * - no invalid interval
     * - no midnight overflow
     * - no same-pump overlap
     * - anti-interference gap with other pumps when antiMin > 0
     */
    fun validateNewInterval(
        newInterval: DoseInterval,
        existing: List<DoseInterval>,
        antiMin: Int,
        expectedEspId: Long? = null
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

        val samePumpConflicts = existing.filter { interval ->
            interval.pump == newInterval.pump &&
                newInterval.startMs < interval.endMs &&
                newInterval.endMs > interval.startMs
        }
        if (samePumpConflicts.isNotEmpty()) {
            val blocking = samePumpConflicts.first()
            val maxConflictEnd = samePumpConflicts.maxOf { it.endMs }
            val nextAllowed = maxConflictEnd + antiGapMs
            return DoseValidationResult(
                isValid = false,
                reason = DoseValidationReason.OVERLAP_SAME_PUMP,
                conflictPumpNum = blocking.pump,
                conflictPump = blocking.pump,
                conflictStartMs = blocking.startMs,
                conflictEndMs = maxConflictEnd,
                nextAllowedStartMs = nextAllowed
            )
        }

        if (antiGapMs <= 0L) {
            return DoseValidationResult(isValid = true)
        }

        var nextAllowedStartMs: Long? = null
        var blockingPumpNum: Int? = null
        var blockingStartMs: Long? = null
        var blockingEndMs: Long? = null
        for (interval in existing) {
            val validBefore = newInterval.endMs + antiGapMs <= interval.startMs
            val validAfter = newInterval.startMs >= interval.endMs + antiGapMs
            if (!validBefore && !validAfter) {
                val candidateStart = interval.endMs + antiGapMs
                if (nextAllowedStartMs == null || candidateStart > nextAllowedStartMs) {
                    nextAllowedStartMs = candidateStart
                    blockingPumpNum = interval.pump
                    blockingStartMs = interval.startMs
                    blockingEndMs = interval.endMs
                }
            }
        }

        if (nextAllowedStartMs != null) {
            return DoseValidationResult(
                isValid = false,
                reason = DoseValidationReason.ANTI_INTERFERENCE_GAP,
                conflictPumpNum = blockingPumpNum,
                conflictPump = blockingPumpNum,
                conflictStartMs = blockingStartMs,
                conflictEndMs = blockingEndMs,
                nextAllowedStartMs = nextAllowedStartMs
            )
        }

        return DoseValidationResult(isValid = true)
    }
}
