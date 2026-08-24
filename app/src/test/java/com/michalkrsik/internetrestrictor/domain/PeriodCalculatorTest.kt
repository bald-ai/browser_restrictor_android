package com.michalkrsik.internetrestrictor.domain

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class PeriodCalculatorTest {
    @Test
    fun localDayKeyUsesRequestedTimezone() {
        val zone = ZoneId.of("Europe/Prague")
        val instant = ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val keys = PeriodCalculator.keysAt(instant, zone)
        assertEquals("2026-07-10", keys.daily)
    }

    @Test
    fun timeBeforeFourBelongsToPreviousLogicalDay() {
        val zone = ZoneId.of("Europe/Prague")
        val instant = ZonedDateTime.of(2026, 8, 22, 0, 24, 0, 0, zone).toInstant().toEpochMilli()

        assertEquals("2026-08-21", PeriodCalculator.keysAt(instant, zone).daily)
    }

    @Test
    fun intervalSplitsExactlyAtFourAm() {
        val zone = ZoneId.of("UTC")
        val start = ZonedDateTime.of(2026, 7, 11, 3, 59, 59, 500_000_000, zone).toInstant().toEpochMilli()
        val intervals = PeriodCalculator.splitAtDailyResets(start, start + 1_000, zone)
        assertEquals(listOf(500L, 500L), intervals.map { it.durationMs })
    }

    @Test
    fun dstDayUsesLocalMidnightRatherThanTwentyFourHours() {
        val zone = ZoneId.of("Europe/Prague")
        val start = LocalDateTime.of(2026, 3, 28, 4, 0).atZone(zone).toInstant().toEpochMilli()
        val next = PeriodCalculator.nextDailyReset(start, zone)
        assertEquals(23 * HOUR_MS, next - start)
    }

    @Test
    fun budgetIntervalSplitsExactlyAtTwentyHundred() {
        val zone = ZoneId.of("Europe/Prague")
        val start = ZonedDateTime.of(2026, 7, 10, 19, 59, 59, 500_000_000, zone)
            .toInstant()
            .toEpochMilli()

        val intervals = PeriodCalculator.splitAtBudgetBoundaries(start, start + 1_000, zone)

        assertEquals(listOf(500L, 500L), intervals.map { it.durationMs })
    }
}
