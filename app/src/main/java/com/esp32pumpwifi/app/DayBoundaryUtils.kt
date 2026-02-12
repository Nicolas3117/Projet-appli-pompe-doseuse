package com.esp32pumpwifi.app

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object DayBoundaryUtils {

    data class DayRange(val dayStartMs: Long, val dayEndMsExclusive: Long)

    fun dayRange(day: LocalDate, zone: ZoneId): DayRange {
        val start = day.atStartOfDay(zone).toInstant().toEpochMilli()
        val nextStart = day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return DayRange(dayStartMs = start, dayEndMsExclusive = nextStart)
    }

    fun isInDay(t: Instant, day: LocalDate, tz: ZoneId): Boolean {
        val start = day.atStartOfDay(tz).toInstant()
        val nextStart = day.plusDays(1).atStartOfDay(tz).toInstant()
        return !t.isBefore(start) && t.isBefore(nextStart)
    }

    fun instantAtOffset(day: LocalDate, offsetMs: Long, zone: ZoneId): Instant {
        val start = day.atStartOfDay(zone).toInstant()
        return start.plusMillis(offsetMs)
    }
}

