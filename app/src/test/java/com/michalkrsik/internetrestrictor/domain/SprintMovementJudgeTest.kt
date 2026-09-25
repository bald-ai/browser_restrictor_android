package com.michalkrsik.internetrestrictor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SprintMovementJudgeTest {

    private fun location(
        lat: Double,
        lon: Double,
        elapsedMs: Long,
        accuracy: Float = 5.0f,
        speedMps: Float? = null,
    ): SprintLocation = SprintLocation(
        latitude = lat,
        longitude = lon,
        timeWallMs = 1_700_000_000_000L + elapsedMs,
        elapsedRealtimeMs = elapsedMs,
        accuracyMeters = accuracy,
        speedMps = speedMps,
    )

    @Test
    fun sustainedFourSecondDipSeparatesTwoQualifyingEfforts() {
        val judge = SprintMovementJudge()
        judge.onStart(0)
        var latitude = 50.0875
        var elapsed = 0L
        judge.onLocation(location(latitude, 14.4211, elapsed, accuracy = 5f, speedMps = 0f))
        fun steps(count: Int, metersPerSecond: Float) {
            repeat(count) {
                elapsed += 1000
                latitude += metersPerSecond / 111_195.0
                judge.onLocation(location(latitude, 14.4211, elapsed, accuracy = 5f, speedMps = metersPerSecond))
            }
        }
        steps(8, 5f)
        steps(4, 3.2f)
        steps(8, 5f)
        val result = judge.onStop(elapsed)
        assertEquals("A dip beyond 3.5 seconds closes the first effort", 2, result.qualifyingEffortCount)
        assertTrue(result.qualifyingDistanceMeters in 85..95)
        assertEquals(0L, result.setsEarned)
    }

    @Test
    fun oneTwoSecondPositionJumpCannotCreditSprintDistance() {
        val judge = SprintMovementJudge()
        judge.onStart(0)
        judge.onLocation(location(50.0875, 14.4211, 0, accuracy = 5f, speedMps = null))
        judge.onLocation(location(50.0875 + 10.0 / 111_195, 14.4211, 2000, accuracy = 5f, speedMps = null))
        val result = judge.onStop(2000)
        assertTrue(result.totalRecordedDistanceMeters in 9.0..11.0)
        assertEquals(0L, result.qualifyingDistanceMeters)
        assertEquals(0, result.qualifyingEffortCount)
        assertEquals(0L, result.newRemainderMeters)
    }

    @Test
    fun stationaryJitterIsRejectedWithZeroQualifyingDistance() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        val baseLat = 50.0875
        val baseLon = 14.4211

        // 15 fixes, 1 second apart, jittering randomly within 1-2 meters
        for (i in 1..15) {
            val jitterLat = baseLat + (if (i % 2 == 0) 0.00001 else -0.00001)
            val jitterLon = baseLon + (if (i % 3 == 0) 0.00001 else 0.0)
            val state = judge.onLocation(
                location(jitterLat, jitterLon, i * 1000L, accuracy = 8f, speedMps = 0.3f),
            )
            assertEquals(0L, state.qualifyingDistanceMeters)
        }

        val result = judge.onStop(16_000L)
        assertEquals(0L, result.qualifyingDistanceMeters)
        assertEquals(0L, result.setsEarned)
        assertEquals(0L, result.newRemainderMeters)
        assertTrue(result.isGpsReliable)
    }

    @Test
    fun walkingIsRejectedWithZeroQualifyingDistance() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        // Walking at 1.4 m/s (approx 5 km/h) for 30 seconds = ~42 meters
        var currentLat = 50.0875
        val currentLon = 14.4211

        for (i in 1..30) {
            currentLat += 0.0000126
            val state = judge.onLocation(
                location(currentLat, currentLon, i * 1000L, accuracy = 6f, speedMps = 1.4f),
            )
            assertEquals(0L, state.qualifyingDistanceMeters)
        }

        val result = judge.onStop(31_000L)
        assertEquals(0L, result.qualifyingDistanceMeters)
        assertEquals(0L, result.setsEarned)
        assertTrue(result.isGpsReliable)
    }

    @Test
    fun multipleShortBelow15EffortsNeverAccumulateCredit() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        var lat = 50.0875
        val lon = 14.4211
        var t = 1000L

        // Initial fix
        judge.onLocation(location(lat, lon, t, speedMps = 0f))

        // Effort 1: Two 1-second steps at 3.7 m/s (~13.3 km/h, 3.7m displacement per second)
        // 3.7 m/s latitude step is ~0.0000332 degrees/sec
        for (i in 1..2) {
            t += 1000L
            lat += 0.0000332
            val state = judge.onLocation(location(lat, lon, t, speedMps = 3.7f))
            assertEquals(0L, state.qualifyingDistanceMeters)
        }

        // Pause / walk for 5 seconds
        for (i in 1..5) {
            t += 1000L
            lat += 0.000005
            val state = judge.onLocation(location(lat, lon, t, speedMps = 0.5f))
            assertEquals(0L, state.qualifyingDistanceMeters)
        }

        // Effort 2: Three 1-second steps at 3.7 m/s
        for (i in 1..3) {
            t += 1000L
            lat += 0.0000332
            val state = judge.onLocation(location(lat, lon, t, speedMps = 3.7f))
            assertEquals(0L, state.qualifyingDistanceMeters)
        }

        val result = judge.onStop(t + 1000L)
        assertEquals(0L, result.qualifyingDistanceMeters)
        assertEquals(0L, result.setsEarned)
        assertEquals(0L, result.newRemainderMeters)
    }

    @Test
    fun coordinateJitterAt13KmhDoesNotQualifyWhenReportedSpeedIs13Kmh() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        var lat = 50.0875
        val lon = 14.4211
        var t = 1000L

        judge.onLocation(location(lat, lon, t, speedMps = 3.6f))

        // Running at reported speed 3.6 m/s (~13 km/h), but coordinates jump 4.5m per second (~16.2 km/h)
        for (i in 1..20) {
            t += 1000L
            lat += 0.0000405 // ~4.5m
            val state = judge.onLocation(location(lat, lon, t, accuracy = 5f, speedMps = 3.6f))
            assertEquals(0L, state.qualifyingDistanceMeters)
        }

        val result = judge.onStop(t + 1000L)
        assertEquals("Effort with reported speed 13 km/h must not qualify", 0L, result.qualifyingDistanceMeters)
        assertEquals(0L, result.setsEarned)
    }

    @Test
    fun isolatedSingleGpsCoordinateSpikeDoesNotQualify() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        val lat = 50.0875
        val lon = 14.4211

        judge.onLocation(location(lat, lon, 1000L, speedMps = 0f))
        // 1 single spike: 8m coordinate jump in 1 second, missing speed field
        judge.onLocation(location(lat + 0.000072, lon, 2000L, speedMps = null))
        // Immediately return to stationary
        judge.onLocation(location(lat + 0.000072, lon, 3000L, speedMps = 0f))

        val result = judge.onStop(4000L)
        assertEquals(0L, result.qualifyingDistanceMeters)
        assertEquals(0L, result.setsEarned)
    }

    @Test
    fun completionFollowedBySlowingPreservesEarnedSetAndMatchesLive() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        var lat = 50.0875
        val lon = 14.4211
        var t = 1000L

        // Initial fix
        judge.onLocation(location(lat, lon, t, speedMps = 0f))

        // Sprints for 31 seconds at 4.2 m/s (~15.12 km/h)
        // 4.2 m/s lat step = ~0.00003774 degrees/sec
        // Total distance = 31 * 4.2 = 130.2 meters
        for (i in 1..31) {
            t += 1000L
            lat += 0.00003774
            judge.onLocation(location(lat, lon, t, accuracy = 5f, speedMps = 4.2f))
        }

        // Check live state right when sprint reached 130m
        val liveAtQualification = judge.currentLiveState()
        assertTrue("Live distance should be ~130m, was ${liveAtQualification.qualifyingDistanceMeters}", liveAtQualification.qualifyingDistanceMeters >= 120L)
        assertTrue("Should be qualified live", liveAtQualification.isQualified)
        assertEquals("Should have earned 1 set live", 1L, liveAtQualification.setsEarned)

        // Now user slows down to 3.7 m/s (13.32 km/h) for 10 seconds (phone retrieval / cooldown)
        // If combined naively, whole 41s average would drop to 14.68 km/h (< 15 km/h)
        for (i in 1..10) {
            t += 1000L
            lat += 0.0000332
            judge.onLocation(location(lat, lon, t, accuracy = 5f, speedMps = 3.7f))
        }

        // Check live state during and after the slowdown
        val liveAfterSlowdown = judge.currentLiveState()
        assertTrue("Live distance must remain >= 120m despite slowing down, was ${liveAfterSlowdown.qualifyingDistanceMeters}", liveAfterSlowdown.qualifyingDistanceMeters >= 120L)
        assertTrue("isQualified must remain true", liveAfterSlowdown.isQualified)
        assertEquals("setsEarned must remain 1", 1L, liveAfterSlowdown.setsEarned)

        // User taps Stop
        val result = judge.onStop(t + 500L)
        assertTrue("Result distance must be >= 120m, was ${result.qualifyingDistanceMeters}", result.qualifyingDistanceMeters >= 120L)
        assertEquals("Final sets earned must be 1", 1L, result.setsEarned)
        assertEquals("Live and final qualifying distance must agree", liveAfterSlowdown.qualifyingDistanceMeters, result.qualifyingDistanceMeters)
        assertTrue("Average speed of qualified sprint must be >= 14.95 km/h, was ${result.averageSpeedKmh}", result.averageSpeedKmh >= SprintMovementJudge.MIN_EFFORT_QUALIFYING_SPEED_KMH)
    }

    @Test
    fun cleanSprintAt17KmhQualifiesAndEarnsSet() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        var currentLat = 50.0875
        val currentLon = 14.4211

        // 2 seconds phone handling / standing
        judge.onLocation(location(currentLat, currentLon, 1000L, speedMps = 0.1f))
        judge.onLocation(location(currentLat, currentLon, 2000L, speedMps = 0.2f))

        // Running at 4.8 m/s (17.28 km/h) for 27 seconds = ~130 meters
        for (i in 3..29) {
            currentLat += 0.00004316
            judge.onLocation(
                location(currentLat, currentLon, i * 1000L, accuracy = 5f, speedMps = 4.8f),
            )
        }

        // 2 seconds slowdown / phone handling
        judge.onLocation(location(currentLat, currentLon, 30_000L, speedMps = 1.0f))
        judge.onLocation(location(currentLat, currentLon, 31_000L, speedMps = 0.2f))

        val result = judge.onStop(32_000L)
        assertTrue("Distance should be approx 130m, was ${result.qualifyingDistanceMeters}", result.qualifyingDistanceMeters in 125L..135L)
        assertEquals(1L, result.setsEarned)
        assertTrue("Remainder should be around 10m, was ${result.newRemainderMeters}", result.newRemainderMeters in 5L..15L)
        assertTrue("Speed should be ~17 km/h, was ${result.averageSpeedKmh}", result.averageSpeedKmh >= 15.0)
        assertTrue(result.isGpsReliable)
    }

    @Test
    fun briefSpeedDipIsBridgedAndEffortRemainsValid() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        var currentLat = 50.0875
        val currentLon = 14.4211

        judge.onLocation(location(currentLat, currentLon, 0L, speedMps = 0f))

        // Sprint part 1: 12 seconds at 4.8 m/s (~58m)
        for (i in 1..12) {
            currentLat += 0.00004316
            judge.onLocation(location(currentLat, currentLon, i * 1000L, accuracy = 5f, speedMps = 4.8f))
        }

        // 1 brief dip fix: 1 second at 3.2 m/s (approx 11.5 km/h)
        currentLat += 0.00002877
        judge.onLocation(location(currentLat, currentLon, 13_000L, accuracy = 5f, speedMps = 3.2f))

        // Sprint part 2: 14 seconds at 4.8 m/s (~67m)
        for (i in 14..27) {
            currentLat += 0.00004316
            judge.onLocation(location(currentLat, currentLon, i * 1000L, accuracy = 5f, speedMps = 4.8f))
        }

        val result = judge.onStop(28_000L)
        assertTrue("Qualifying distance was ${result.qualifyingDistanceMeters}", result.qualifyingDistanceMeters >= 120L)
        assertEquals(1L, result.setsEarned)
        assertTrue(result.averageSpeedKmh >= 15.0)
        assertTrue(result.isGpsReliable)
    }

    @Test
    fun shortQualifyingRunsCarryOverAcrossRecordings() {
        // Run 1: 80m at 16 km/h (starts with 0 remainder)
        val judge1 = SprintMovementJudge(startingRemainderMeters = 0L)
        judge1.onStart(0L)

        var lat = 50.0875
        val lon = 14.4211
        judge1.onLocation(location(lat, lon, 0L, speedMps = 0f))

        // 4.5 m/s (16.2 km/h) for 18 seconds = 81m
        for (i in 1..18) {
            lat += 0.00004046
            judge1.onLocation(location(lat, lon, i * 1000L, accuracy = 5f, speedMps = 4.5f))
        }
        val result1 = judge1.onStop(19_000L)
        assertEquals(0L, result1.setsEarned)
        assertTrue("Remainder should be ~81m, was ${result1.newRemainderMeters}", result1.newRemainderMeters in 75L..85L)

        // Run 2: starts with carryover remainder from run 1
        val judge2 = SprintMovementJudge(startingRemainderMeters = result1.newRemainderMeters)
        judge2.onStart(0L)
        judge2.onLocation(location(lat, lon, 0L, speedMps = 0f))

        // 4.5 m/s for 15 seconds = ~67m (combined ~148m >= 120m)
        for (i in 1..15) {
            lat += 0.00004046
            judge2.onLocation(location(lat, lon, i * 1000L, accuracy = 5f, speedMps = 4.5f))
        }
        val result2 = judge2.onStop(16_000L)
        assertEquals(1L, result2.setsEarned)
        assertTrue("New remainder should be around 28m, was ${result2.newRemainderMeters}", result2.newRemainderMeters in 20L..35L)
    }

    @Test
    fun multipleEffortsWithWalkingInBetweenQualifyTogether() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        var lat = 50.0875
        val lon = 14.4211

        judge.onLocation(location(lat, lon, 0L, speedMps = 0f))

        // Effort 1: 14 seconds at 4.8 m/s = ~67m
        for (i in 1..14) {
            lat += 0.00004316
            judge.onLocation(location(lat, lon, i * 1000L, accuracy = 5f, speedMps = 4.8f))
        }

        // Walking for 10 seconds at 1.4 m/s (effort terminates, walking not counted)
        for (i in 15..24) {
            lat += 0.0000126
            judge.onLocation(location(lat, lon, i * 1000L, accuracy = 5f, speedMps = 1.4f))
        }

        // Effort 2: 14 seconds at 4.8 m/s = ~67m
        for (i in 25..38) {
            lat += 0.00004316
            judge.onLocation(location(lat, lon, i * 1000L, accuracy = 5f, speedMps = 4.8f))
        }

        val result = judge.onStop(39_000L)
        assertEquals(2, result.qualifyingEffortCount)
        assertTrue("Total qualifying distance should be ~134m, was ${result.qualifyingDistanceMeters}", result.qualifyingDistanceMeters in 125L..145L)
        assertEquals(1L, result.setsEarned)
    }

    @Test
    fun impossibleTeleportJumpIsFiltered() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        val lat = 50.0875
        val lon = 14.4211

        judge.onLocation(location(lat, lon, 1000L, speedMps = 4.5f))
        // Sudden 500m jump in 1 second (500 m/s)
        judge.onLocation(location(lat + 0.0045, lon, 2000L, speedMps = 500f))

        val result = judge.onStop(3000L)
        assertEquals(0L, result.qualifyingDistanceMeters)
    }

    @Test
    fun missingSpeedFieldIsCalculatedFromDisplacement() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        var lat = 50.0875
        val lon = 14.4211

        judge.onLocation(location(lat, lon, 0L, speedMps = null))

        // Send fixes with speedMps = null at 4.8 m/s
        for (i in 1..28) {
            lat += 0.00004316
            judge.onLocation(location(lat, lon, i * 1000L, accuracy = 5f, speedMps = null))
        }

        val result = judge.onStop(29_000L)
        assertTrue("Qualifying distance should be ~130m, was ${result.qualifyingDistanceMeters}", result.qualifyingDistanceMeters >= 120L)
        assertEquals(1L, result.setsEarned)
        assertTrue(result.averageSpeedKmh >= 15.0)
    }

    @Test
    fun significantGpsGapMarksReliabilityAsFalse() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        judge.onLocation(location(50.0875, 14.4211, 1000L, speedMps = 4.5f))
        judge.onLocation(location(50.0876, 14.4211, 2000L, speedMps = 4.5f))
        // 12 second gap where GPS drops out
        judge.onLocation(location(50.0880, 14.4211, 14_000L, speedMps = 4.5f))

        val result = judge.onStop(15_000L)
        assertFalse("GPS gap should trigger unreliable GPS", result.isGpsReliable)
    }

    @Test
    fun highRatioOfNoisyFixesMarksReliabilityAsFalse() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        var lat = 50.0875
        val lon = 14.4211
        var t = 1000L

        // 2 good fixes
        judge.onLocation(location(lat, lon, t, accuracy = 10f, speedMps = 4.5f))
        t += 1000L
        lat += 0.00004
        judge.onLocation(location(lat, lon, t, accuracy = 10f, speedMps = 4.5f))

        // 4 noisy fixes with accuracy 50m (exceeding 30m max accuracy threshold) -> 4 out of 6 fixes rejected (66.7% > 60%)
        for (i in 1..4) {
            t += 1000L
            lat += 0.00004
            judge.onLocation(location(lat, lon, t, accuracy = 50f, speedMps = 4.5f))
        }

        val result = judge.onStop(t + 1000L)
        assertFalse("66.7% rejected fixes (> 60%) must mark reliability as false", result.isGpsReliable)
    }

    @Test
    fun reliableShortEffortCarriesQualifyingDistanceWithoutMarkingUnreliable() {
        val judge = SprintMovementJudge(startingRemainderMeters = 0L)
        judge.onStart(0L)

        var lat = 50.0875
        val lon = 14.4211
        judge.onLocation(location(lat, lon, 0L, speedMps = 0f))

        // Run 40 meters at 16 km/h (9 seconds)
        for (i in 1..9) {
            lat += 0.00004046
            judge.onLocation(location(lat, lon, i * 1000L, accuracy = 4f, speedMps = 4.5f))
        }

        val result = judge.onStop(10_000L)
        assertTrue(result.isGpsReliable)
        assertTrue("Distance should be around 40m, was ${result.qualifyingDistanceMeters}", result.qualifyingDistanceMeters in 35L..45L)
        assertEquals(0L, result.setsEarned)
    }
}
