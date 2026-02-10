package com.esp32pumpwifi.app

import android.util.Log
import java.util.Locale

object ScheduleAddMergeUtils {

    private const val MAX_SCHEDULES = 12
    private const val DAY_MS = 24L * 60L * 60L * 1000L
    private const val HOUR_MS = 3_600_000L
    private const val MINUTE_MS = 60_000L
    private const val TAG_TIME_BUG = "TIME_BUG"

    data class MergeResult(
        val merged: List<PumpSchedule>,
        val addedCount: Int,
        val skippedDuplicateCount: Int,
        val wasAlreadyFull: Boolean
    )

    fun isValidMsOfDay(ms: Long): Boolean = ms in 0 until DAY_MS

    fun toTimeString(ms: Long): String {
        if (!isValidMsOfDay(ms)) {
            Log.w(TAG_TIME_BUG, "invalid_ms_in_toTimeString ms=$ms expectedRange=[0,${DAY_MS - 1}]")
        }
        val normalized = ((ms % DAY_MS) + DAY_MS) % DAY_MS
        val hours = ((normalized / HOUR_MS) % 24).toInt()
        val minutes = ((normalized % HOUR_MS) / MINUTE_MS).toInt()
        return String.format(Locale.getDefault(), "%02d:%02d", hours, minutes)
    }

    fun mergeSchedules(
        existing: List<PumpSchedule>,
        newTimes: List<String>,
        pumpNumber: Int,
        quantityTenthForNewLines: Int,
        cap: Int = MAX_SCHEDULES
    ): MergeResult {
        if (existing.size >= cap) {
            return MergeResult(
                merged = existing.take(cap).sortedBy { sortKeyMinutes(it.time) },
                addedCount = 0,
                skippedDuplicateCount = newTimes.size,
                wasAlreadyFull = true
            )
        }

        val base = existing.toMutableList()
        val existingTimes = base.map { it.time }.toMutableSet()

        var added = 0
        var skippedDuplicates = 0

        for (time in newTimes) {
            if (time in existingTimes) {
                skippedDuplicates++
                continue
            }
            if (base.size >= cap) break

            base.add(
                PumpSchedule(
                    pumpNumber = pumpNumber,
                    time = time,
                    quantityTenth = quantityTenthForNewLines,
                    enabled = true
                )
            )
            existingTimes.add(time)
            added++
        }

        val merged = base
            .distinctBy { it.time }
            .sortedBy { sortKeyMinutes(it.time) }
            .take(cap)

        return MergeResult(
            merged = merged,
            addedCount = added,
            skippedDuplicateCount = skippedDuplicates,
            wasAlreadyFull = false
        )
    }


    fun mergeSchedulesWithQuantities(
        existing: List<PumpSchedule>,
        newSchedules: List<PumpSchedule>,
        cap: Int = MAX_SCHEDULES
    ): MergeResult {
        if (existing.size >= cap) {
            return MergeResult(
                merged = existing.take(cap).sortedBy { sortKeyMinutes(it.time) },
                addedCount = 0,
                skippedDuplicateCount = newSchedules.size,
                wasAlreadyFull = true
            )
        }

        val base = existing.toMutableList()
        val existingTimes = base.map { it.time }.toMutableSet()

        var added = 0
        var skippedDuplicates = 0

        for (schedule in newSchedules) {
            if (schedule.time in existingTimes) {
                skippedDuplicates++
                continue
            }
            if (base.size >= cap) break
            base.add(schedule)
            existingTimes.add(schedule.time)
            added++
        }

        val merged = base
            .distinctBy { it.time }
            .sortedBy { sortKeyMinutes(it.time) }
            .take(cap)

        return MergeResult(
            merged = merged,
            addedCount = added,
            skippedDuplicateCount = skippedDuplicates,
            wasAlreadyFull = false
        )
    }

    private fun sortKeyMinutes(time: String): Int {
        val parsed = ScheduleOverlapUtils.parseTimeOrNull(time)
        return if (parsed == null) Int.MAX_VALUE else parsed.first * 60 + parsed.second
    }
}
