package com.esp32pumpwifi.app

import org.junit.Assert.assertEquals
import org.junit.Test

class AntiInterferenceUtilsTest {

    @Test
    fun ceilToMinute_roundsUp() {
        assertEquals(0L, ceilToMinute(0L))
        assertEquals(60_000L, ceilToMinute(1L))
        assertEquals(60_000L, ceilToMinute(60_000L))
        assertEquals(660_000L, ceilToMinute(600_001L))
    }
}
