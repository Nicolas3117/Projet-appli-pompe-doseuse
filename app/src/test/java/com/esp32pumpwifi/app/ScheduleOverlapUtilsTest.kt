package com.esp32pumpwifi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScheduleOverlapUtilsTest {

    @Test
    fun parseTimeOrNull_roundtrip_10_00() {
        val parsed = ScheduleOverlapUtils.parseTimeOrNull("10:00")
        assertNotNull(parsed)
        assertEquals(10 to 0, parsed)
    }
}
