package com.esp32pumpwifi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleAddMergeUtilsTest {

    @Test
    fun merge_emptyExisting_unsortedNewTimes_sortedAndCappedTo12() {
        val newTimes = (0..14).map { i -> String.format("%02d:%02d", (23 - i) % 24, (i * 5) % 60) }

        val result = ScheduleAddMergeUtils.mergeSchedules(
            existing = emptyList(),
            newTimes = newTimes,
            pumpNumber = 1,
            quantityTenthForNewLines = 15
        )

        assertEquals(12, result.merged.size)
        assertEquals(result.merged.map { it.time }.sorted(), result.merged.map { it.time })
    }

    @Test
    fun merge_duplicateExactTime_doesNotAddDuplicate() {
        val existing = listOf(
            PumpSchedule(1, "08:30", 10, true)
        )

        val result = ScheduleAddMergeUtils.mergeSchedules(
            existing = existing,
            newTimes = listOf("08:30", "09:00"),
            pumpNumber = 1,
            quantityTenthForNewLines = 20
        )

        assertEquals(2, result.merged.size)
        assertEquals(1, result.addedCount)
        assertEquals(1, result.skippedDuplicateCount)
        assertEquals(listOf("08:30", "09:00"), result.merged.map { it.time })
    }

    @Test
    fun merge_existingAlreadyFull_returnsAlreadyFullAndNoChanges() {
        val existing = (0 until 12).map {
            PumpSchedule(2, String.format("%02d:00", it), 10, true)
        }

        val result = ScheduleAddMergeUtils.mergeSchedules(
            existing = existing,
            newTimes = listOf("13:00", "14:00"),
            pumpNumber = 2,
            quantityTenthForNewLines = 10
        )

        assertTrue(result.wasAlreadyFull)
        assertEquals(12, result.merged.size)
        assertEquals(existing.map { it.time }, result.merged.map { it.time })
    }
}
