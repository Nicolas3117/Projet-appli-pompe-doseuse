package com.esp32pumpwifi.app

import java.util.Locale

object ScheduleAddMergeUtils {

    private const val MAX_SCHEDULES = 12

    data class MergeResult(
        val merged: List<PumpSchedule>,
        val addedCount: Int,
        val skippedDuplicateCount: Int,
        val wasAlreadyFull: Boolean
    )

    fun toTimeString(ms: Long): String {
        val hours = (ms / 3_600_000L).toInt().coerceIn(0, 23)
        val minutes = ((ms % 3_600_000L) / 60_000L).toInt().coerceIn(0, 59)
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

    private fun sortKeyMinutes(time: String): Int {
        val parsed = ScheduleOverlapUtils.parseTimeOrNull(time)
        return if (parsed == null) Int.MAX_VALUE else parsed.first * 60 + parsed.second
    }
}

