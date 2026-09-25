package com.michalkrsik.internetrestrictor.v1

import org.junit.Assert.assertEquals
import org.junit.Test

class GpsDistanceRulesTest {
    // One degree at the equator is approximately 111195 metres. These expected
    // distances use a fixed independent reference, not the tracker distance method.
    private fun point(degrees: Double, time: Long, accuracy: Double = 0.0, speed: Double? = null) =
        GpsPoint(0.0, degrees, time, accuracy, speed)

    private fun distance(mode: String?, vararg points: GpsPoint): Double =
        GpsDistanceTracker(mode).also { tracker -> points.forEach(tracker::add) }.meters

    @Test fun equatorialAndAntipodalDistances() {
        assertEquals(111195.0, GpsDistanceTracker.distance(point(0.0, 0), point(1.0, 0)), 0.1)
        assertEquals(20015086.8, GpsDistanceTracker.distance(point(0.0, 0), point(180.0, 0)), 0.1)
        assertEquals(0.0, GpsDistanceTracker.distance(point(0.0, 0), point(0.0, 0)), 0.0)
    }

    @Test fun walkingAccumulatesNoiseUntilMovementIsLargeEnough() {
        assertEquals(3.336, distance("walk", point(0.0, 0), point(.00001, 1000), point(.00003, 3000)), .001)
    }

    @Test fun inaccurateReadingBreaksTheAnchorAndDoesNotBridgeTheMissingRoute() {
        for (accuracy in listOf(-1.0, 25.01)) {
            assertEquals(0.0, distance("walk", point(0.0, 0), point(.00003, 1000, accuracy), point(.00006, 2000)), 0.0)
        }
        assertEquals(20.015, distance("walk", point(0.0, 0, 25.0), point(.00018, 5000, 25.0)), .001)
    }

    @Test fun longGapReanchorsButFifteenSecondsStillCounts() {
        assertEquals(0.0, distance("walk", point(0.0, 0), point(.00003, 15001)), 0.0)
        assertEquals(3.336, distance("walk", point(0.0, 0), point(.00003, 15000)), .001)
    }

    @Test fun duplicateAndReversedTimeCannotMoveTheAnchor() {
        for (time in listOf(0L, -1L)) {
            assertEquals(3.336, distance("walk", point(0.0, 0), point(.5, time), point(.00003, 1000)), .001)
        }
    }

    @Test fun vehicleAndStationarySectionsReanchorWithoutEarningDistance() {
        for (speed in listOf(0.0, .49, 5.01)) {
            assertEquals(3.336, distance("walk", point(0.0, 0), point(.00003, 1000, speed = speed), point(.00006, 2000, speed = .5)), .001)
        }
        assertEquals(0.0, distance("walk", point(0.0, 0), point(.00005, 1000, speed = 1.0)), 0.0)
        assertEquals(3.336, distance("walk", point(0.0, 0), point(.00003, 1000, speed = 5.0)), .001)
    }

    @Test fun invalidDeviceSpeedUsesPositionSpeed() {
        for (speed in listOf(null, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(3.336, distance("walk", point(0.0, 0), point(.00003, 1000, speed = speed)), .001)
        }
    }

    @Test fun sprintRequiresBothSpeedsWithinItsWindow() {
        for (speed in listOf(null, 15 / 3.6, 35 / 3.6)) {
            assertEquals(5.56, distance("sprint", point(0.0, 0), point(.00005, 1000, speed = speed)), .001)
        }
        for (speed in listOf(4.16, 9.73)) {
            assertEquals(0.0, distance("sprint", point(0.0, 0), point(.00005, 1000, speed = speed)), 0.0)
        }
        assertEquals(0.0, distance("sprint", point(0.0, 0), point(.00003, 1000)), 0.0)
        assertEquals(0.0, distance("sprint", point(0.0, 0), point(.00003, 1000, speed = 5.0)), 0.0)
        assertEquals(0.0, distance("sprint", point(0.0, 0), point(.0001, 1000)), 0.0)
        assertEquals(5.56, distance(null, point(0.0, 0), point(.00005, 1000)), .001)
    }
}
