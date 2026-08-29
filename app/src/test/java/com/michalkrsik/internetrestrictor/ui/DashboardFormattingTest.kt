package com.michalkrsik.internetrestrictor.ui

import com.michalkrsik.internetrestrictor.domain.ExerciseId
import com.michalkrsik.internetrestrictor.ui.challenge.formatExerciseResult
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
    fun exerciseResultUsesDetectedMetricSetsAndCarry() {
        assertEquals(
            "Plank detected · 20 seconds · 1 set earned · 2 seconds carried",
            formatExerciseResult(
                exerciseId = ExerciseId.PLANK,
                measuredValue = 20,
                completedSets = 1,
                remainder = 2,
            ),
        )
        assertEquals(
            "Machine detected · 10 repetitions · 1 set earned · 0 repetitions carried",
            formatExerciseResult(
                exerciseId = ExerciseId.OUTDOOR_LAT_PULLDOWN,
                measuredValue = 10,
                completedSets = 1,
                remainder = 0,
            ),
        )
        assertEquals(
            "Push-ups detected · 6 repetitions · 1 set earned · 1 repetition carried",
            formatExerciseResult(
                exerciseId = ExerciseId.PUSH_UP,
                measuredValue = 6,
                completedSets = 1,
                remainder = 1,
            ),
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
