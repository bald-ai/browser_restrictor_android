package com.michalkrsik.internetrestrictor.ui

import com.michalkrsik.internetrestrictor.ui.challenge.formatPushupResult
import com.michalkrsik.internetrestrictor.ui.dashboard.carryExpirationRemainingMs
import com.michalkrsik.internetrestrictor.ui.dashboard.formatCarryExpiration
import com.michalkrsik.internetrestrictor.ui.dashboard.formatMonthlyCycleRange
import com.michalkrsik.internetrestrictor.domain.MonthlyPushupCycle
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class DashboardFormattingTest {
    @Test
    fun carryExpirationCountsDownToFourAmReset() {
        val zone = ZoneId.of("Europe/Prague")
        val now = ZonedDateTime.of(2026, 8, 22, 3, 59, 30, 0, zone)
            .toInstant()
            .toEpochMilli()

        val remaining = carryExpirationRemainingMs(now, zone)

        assertEquals(30_000L, remaining)
        assertEquals("00:00:30", formatCarryExpiration(remaining))
    }

    @Test
    fun pushupResultIncludesCountGrantAndCarry() {
        assertEquals(
            "6 push-ups counted · 6 minutes granted · 0/5 carried over",
            formatPushupResult(repetitionCount = 6, earnedMinutes = 6, carriedRepetitions = 0),
        )
    }

    @Test
    fun monthlyCycleRangeShowsNinthToNinth() {
        assertEquals(
            "9 Aug – 9 Sep",
            formatMonthlyCycleRange(MonthlyPushupCycle("2026-08-09", 42)),
        )
    }
}
