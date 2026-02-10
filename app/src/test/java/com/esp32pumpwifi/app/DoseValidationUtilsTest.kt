package com.esp32pumpwifi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoseValidationUtilsTest {

    @Test
    fun overlapIntraPump_detected() {
        val existing = listOf(DoseInterval(pump = 1, startMs = 10_000L, endMs = 20_000L))
        val candidate = DoseInterval(pump = 1, startMs = 15_000L, endMs = 18_000L)

        val result = DoseValidationUtils.validateNewInterval(candidate, existing, antiMin = 0)

        assertFalse(result.isValid)
        assertEquals(DoseValidationReason.OVERLAP_SAME_PUMP, result.reason)
        assertEquals(1, result.conflictPump)
    }


    @Test
    fun overlapIntraPump_returnsMaxEndAsNextAllowedStart() {
        val existing = listOf(
            DoseInterval(pump = 1, startMs = 10_000L, endMs = 25_000L),
            DoseInterval(pump = 1, startMs = 15_000L, endMs = 30_000L),
            DoseInterval(pump = 2, startMs = 11_000L, endMs = 40_000L)
        )
        val candidate = DoseInterval(pump = 1, startMs = 12_000L, endMs = 20_000L)

        val result = DoseValidationUtils.validateNewInterval(candidate, existing, antiMin = 0)

        assertFalse(result.isValid)
        assertEquals(DoseValidationReason.OVERLAP_SAME_PUMP, result.reason)
        assertEquals(30_000L, result.nextAllowedStartMs)
    }

    @Test
    fun antiInterference_detectedWithNextAllowedStart() {
        val existing = listOf(DoseInterval(pump = 2, startMs = 60_000L, endMs = 120_000L))
        val candidate = DoseInterval(pump = 1, startMs = 130_000L, endMs = 150_000L)

        val result = DoseValidationUtils.validateNewInterval(candidate, existing, antiMin = 1)

        assertFalse(result.isValid)
        assertEquals(DoseValidationReason.ANTI_INTERFERENCE_GAP, result.reason)
        assertEquals(180_000L, result.nextAllowedStartMs)
    }

    @Test
    fun overflowMidnight_detected() {
        val candidate = DoseInterval(pump = 1, startMs = 86_399_000L, endMs = 86_401_000L)

        val result = DoseValidationUtils.validateNewInterval(candidate, emptyList(), antiMin = 0)

        assertFalse(result.isValid)
        assertEquals(DoseValidationReason.OVERFLOW_MIDNIGHT, result.reason)
    }

    @Test
    fun antiPositive_forbidsInterPumpSimultaneous() {
        val existing = listOf(DoseInterval(pump = 2, startMs = 10_000L, endMs = 30_000L))
        val candidate = DoseInterval(pump = 1, startMs = 20_000L, endMs = 25_000L)

        val result = DoseValidationUtils.validateNewInterval(candidate, existing, antiMin = 1)

        assertFalse(result.isValid)
        assertEquals(DoseValidationReason.ANTI_INTERFERENCE_GAP, result.reason)
        assertEquals(90_000L, result.nextAllowedStartMs)
    }

    @Test
    fun antiZero_ignoresCrossPump_butKeepsIntra() {
        val crossPump = listOf(DoseInterval(pump = 2, startMs = 1_000L, endMs = 5_000L))
        val crossCandidate = DoseInterval(pump = 1, startMs = 2_000L, endMs = 4_000L)
        val crossResult = DoseValidationUtils.validateNewInterval(crossCandidate, crossPump, antiMin = 0)
        assertTrue(crossResult.isValid)

        val samePump = listOf(DoseInterval(pump = 1, startMs = 1_000L, endMs = 5_000L))
        val sameCandidate = DoseInterval(pump = 1, startMs = 2_000L, endMs = 4_000L)
        val sameResult = DoseValidationUtils.validateNewInterval(sameCandidate, samePump, antiMin = 0)
        assertFalse(sameResult.isValid)
        assertEquals(DoseValidationReason.OVERLAP_SAME_PUMP, sameResult.reason)
    }
}
