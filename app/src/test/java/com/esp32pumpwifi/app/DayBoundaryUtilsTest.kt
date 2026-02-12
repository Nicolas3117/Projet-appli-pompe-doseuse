package com.esp32pumpwifi.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DayBoundaryUtilsTest {

    @Test
    fun `offset boundaries map to expected day`() {
        val tz = ZoneId.of("Europe/Paris")
        val day = LocalDate.of(2026, 2, 13)

        val at0000 = DayBoundaryUtils.instantAtOffset(day, 0L, tz)
        val at0001 = DayBoundaryUtils.instantAtOffset(day, 60_000L, tz)
        val at235959 = DayBoundaryUtils.instantAtOffset(day, 86_399_000L, tz)
        val at2400 = DayBoundaryUtils.instantAtOffset(day, 86_400_000L, tz)

        assertTrue(DayBoundaryUtils.isInDay(at0000, day, tz))
        assertTrue(DayBoundaryUtils.isInDay(at0001, day, tz))
        assertTrue(DayBoundaryUtils.isInDay(at235959, day, tz))
        assertFalse(DayBoundaryUtils.isInDay(at2400, day, tz))

        val nextDay = day.plusDays(1)
        assertTrue(DayBoundaryUtils.isInDay(at2400, nextDay, tz))
    }

    @Test
    fun `day transition from 23_59 to 00_00 keeps unique day ownership`() {
        val tz = ZoneId.of("Europe/Paris")
        val day = LocalDate.of(2026, 2, 13)
        val nextDay = day.plusDays(1)

        val at235959 = DayBoundaryUtils.instantAtOffset(day, 86_399_000L, tz)
        val at0000NextDay = DayBoundaryUtils.instantAtOffset(day, 86_400_000L, tz)

        assertTrue(DayBoundaryUtils.isInDay(at235959, day, tz))
        assertFalse(DayBoundaryUtils.isInDay(at235959, nextDay, tz))

        assertFalse(DayBoundaryUtils.isInDay(at0000NextDay, day, tz))
        assertTrue(DayBoundaryUtils.isInDay(at0000NextDay, nextDay, tz))
    }
}
