package com.michalkrsik.internetrestrictor.v1

import android.app.Application
import android.location.Location
import android.os.SystemClock
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Only in-memory callbacks; no service lifecycle, location manager, app or device is started. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class GpsRecordingProgressTest {
    private fun point(longitude: Double, time: Long, accuracy: Double = 0.0) =
        GpsPoint(0.0, longitude, time, accuracy, null)

    @After fun resetState() { V1GpsService.state.value = V1GpsState() }

    @Test fun chronologicalEvidenceKeepsQualifiedDistanceAndAccuracyGaps() {
        val progress = GpsRecordingProgress("walk")
        progress.add(point(0.0, 1000), false)
        progress.add(point(.00003, 2000), false)
        assertEquals(3.336, progress.meters, .001)
        progress.add(point(.00006, 3000, 26.0), false)
        progress.add(point(.00009, 4000), false)
        assertEquals(3.336, progress.meters, .001)
        assertNull(progress.failure)
    }

    @Test fun timeMustIncreaseAgainstEveryRetainedPointIncludingNoiseAndBadAccuracy() {
        for (accuracy in listOf(0.0, 26.0)) for (invalidTime in listOf(2999L, 3000L)) {
            val progress = GpsRecordingProgress("walk")
            progress.add(point(0.0, 1000), false)
            progress.add(point(.00003, 2000), false)
            progress.add(point(.000031, 3000, accuracy), false)
            progress.add(point(.00006, invalidTime), false)
            val failure = progress.failure
            assertNotNull("Invalid time after accuracy $accuracy must permanently invalidate the recording", failure)
            assertTrue(failure!!.contains("timestamps"))
            assertEquals(0.0, progress.meters, 0.0)
            progress.add(point(.00009, 5000), false)
            assertEquals(failure, progress.failure)
            assertEquals(0.0, progress.meters, 0.0)
        }
    }

    @Test fun mockEvidencePermanentlyInvalidatesPreviouslyCountedDistance() {
        val progress = GpsRecordingProgress("walk")
        progress.add(point(0.0, 1000), false)
        progress.add(point(.00003, 2000), false)
        progress.add(point(.00006, 3000), true)
        progress.add(point(.00009, 4000), false)
        assertEquals("Mock GPS detected. This recording cannot earn rewards.", progress.failure)
        assertEquals(0.0, progress.meters, 0.0)
    }

    @Test fun callbackRetainsInvalidEvidenceAndShowsStickyFailureInsteadOfEarnableDistance() {
        val service = V1GpsService()
        V1GpsService.state.value = V1GpsState(recording = true)
        fun deliver(longitude: Double, time: Long, age: Long = 0) {
            service.onLocationChanged(Location("gps").apply {
                latitude = 0.0; this.longitude = longitude; this.time = time; accuracy = 0f
                elapsedRealtimeNanos = (SystemClock.elapsedRealtime() - age) * 1_000_000
            })
        }
        deliver(0.0, 1000)
        deliver(.00003, 2000)
        assertEquals(3.336, V1GpsService.state.value.meters, .001)
        deliver(.00006, 2000)
        deliver(.00009, 3000)
        deliver(.5, 4000, 10001) // stale delivery remains excluded before evidence capture
        val pointsField = V1GpsService::class.java.getDeclaredField("points").apply { isAccessible = true }
        val points = pointsField.get(service) as JSONArray
        assertEquals(4, points.length())
        assertEquals(2000, points.getJSONObject(2).getLong("time"))
        assertEquals(.00006, points.getJSONObject(2).getDouble("longitude"), 0.0)
        assertTrue(V1GpsService.state.value.status.contains("cannot earn rewards"))
        assertEquals(0.0, V1GpsService.state.value.meters, 0.0)
    }
}
