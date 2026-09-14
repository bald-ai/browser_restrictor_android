package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class MonthlyPushupCalculatorTest {
    private val prague = ZoneId.of("Europe/Prague")

    @Test
    fun cycleChangesExactlyAtPragueMidnightOnTheNinth() {
        val before = ZonedDateTime.of(2026, 9, 8, 23, 59, 59, 999_000_000, prague)
            .toInstant()
            .toEpochMilli()
        val boundary = ZonedDateTime.of(2026, 9, 9, 0, 0, 0, 0, prague)
            .toInstant()
            .toEpochMilli()

        assertEquals("2026-08-09", MonthlyPushupCalculator.cycleStartKey(before, prague))
        assertEquals("2026-09-09", MonthlyPushupCalculator.cycleStartKey(boundary, prague))
    }

    @Test
    fun skippedCyclesArePreservedAsZeroInsteadOfOverwritten() {
        val november = ZonedDateTime.of(2026, 11, 10, 12, 0, 0, 0, prague)
            .toInstant()
            .toEpochMilli()
        val existing = listOf(MonthlyPushupCycle("2026-08-09", 42))

        val normalized = MonthlyPushupCalculator.ensureCurrentCycle(existing, november, prague)

        assertEquals(
            listOf(
                MonthlyPushupCycle("2026-08-09", 42),
                MonthlyPushupCycle("2026-09-09", 0),
                MonthlyPushupCycle("2026-10-09", 0),
                MonthlyPushupCycle("2026-11-09", 0),
            ),
            normalized,
        )
    }

    @Test
    fun addingToCurrentCycleNeverChangesCompletedCycles() {
        val now = ZonedDateTime.of(2026, 9, 10, 12, 0, 0, 0, prague)
            .toInstant()
            .toEpochMilli()
        val cycles = listOf(
            MonthlyPushupCycle("2026-08-09", 42),
            MonthlyPushupCycle("2026-09-09", 3),
        )

        val updated = MonthlyPushupCalculator.addPushups(cycles, 7, now, prague)

        assertEquals(MonthlyPushupCycle("2026-08-09", 42), updated[0])
        assertEquals(MonthlyPushupCycle("2026-09-09", 10), updated[1])
    }
}
