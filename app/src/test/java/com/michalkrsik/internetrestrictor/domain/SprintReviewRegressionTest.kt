package com.michalkrsik.internetrestrictor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SprintReviewRegressionTest {
    private fun fix(metersNorth: Double, second: Int, speed: Float?, accuracy: Float = 5f) = SprintLocation(
        latitude = metersNorth / 111194.9266,
        longitude = 0.0,
        timeWallMs = 1700000000000L + second * 1000,
        elapsedRealtimeMs = second * 1000L,
        accuracyMeters = accuracy,
        speedMps = speed,
    )

    @Test fun initialGpsBlackoutTriggersUnreliableFallback() {
        val judge = SprintMovementJudge()
        judge.onStart(0)
        for (i in 10..42) judge.onLocation(fix((i - 10) * 4.5, i, 4.5f))
        val result = judge.onStop(43000)
        assertTrue("The first ten seconds lacked GPS: $result", !result.isGpsReliable)
    }

    @Test fun intermittentMissingSpeedDoesNotTurnRunningIntoZeroSpeed() {
        val judge = SprintMovementJudge()
        judge.onStart(0)
        for (i in 0..32) judge.onLocation(fix(i * 4.5, i, if (i % 2 == 0) 4.5f else null))
        val result = judge.onStop(33000)
        assertTrue("Clean 144m at16.2kmh with optional speed missing on alternate fixes must qualify: $result", result.qualifyingDistanceMeters >= 120)
    }

    @Test fun modestDistanceUndercountDoesNotRejectValidReportedRunningSpeed() {
        val judge = SprintMovementJudge()
        judge.onStart(0)
        // Reliable speed15.12kmh; coordinates undercount movement by5%, well inside accepted distance allowance.
        for (i in 0..34) judge.onLocation(fix(i * 3.99, i, 4.2f))
        val result = judge.onStop(35000)
        assertTrue("Modest undercount should not erase a completed sprint: $result", result.qualifyingDistanceMeters >= 120 || !result.isGpsReliable)
    }

    @Test fun stationaryJitterWithoutSpeedDoesNotEarnRunningDistance() {
        val judge = SprintMovementJudge()
        judge.onStart(0)
        // Alternating +/-2.5m location error, within the reported8m accuracy radius.
        for (i in 0..30) judge.onLocation(fix(if (i % 2 == 0) -2.5 else 2.5, i, null, 8f))
        val result = judge.onStop(31000)
        assertEquals("Standing still must not generate running credit: $result", 0L, result.qualifyingDistanceMeters)
    }
}
