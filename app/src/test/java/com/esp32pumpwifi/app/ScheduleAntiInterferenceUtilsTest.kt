package com.esp32pumpwifi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduleAntiInterferenceUtilsTest {

    @Test
    fun computeMaxDoses_10minOver60min_returns7() {
        val max = ScheduleAntiInterferenceUtils.computeMaxDoses(
            windowMs = 60L * 60L * 1000L,
            antiOverlapMinutes = 10
        )

        assertEquals(7, max)
    }

    @Test
    fun minAntiMinutesRequired_whenDoseCountTooHigh_returnsExpectedCeil() {
        val minRequired = ScheduleAntiInterferenceUtils.minAntiMinutesRequired(
            windowMs = 60L * 60L * 1000L,
            doseCount = 8
        )

        assertEquals(9, minRequired)
    }

    @Test
    fun computeMaxDoses_antiZero_hasNoConstraint() {
        val max = ScheduleAntiInterferenceUtils.computeMaxDoses(
            windowMs = 5L * 60L * 1000L,
            antiOverlapMinutes = 0
        )

        assertTrue(max > 1_000_000)
    }
}
