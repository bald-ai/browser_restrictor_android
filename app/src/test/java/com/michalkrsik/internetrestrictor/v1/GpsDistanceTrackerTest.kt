package com.michalkrsik.internetrestrictor.v1

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class GpsDistanceTrackerTest {
    // Expected values come from the backend verifier; backend-v1/test/gps-parity.test.mjs checks the same file.
    private val cases = JSONArray(File("../backend-v1/test/fixtures/gps-parity.json").readText())

    @Test fun liveDistanceMatchesServerVerification() {
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val tracker = GpsDistanceTracker(case.getString("mode"))
            val points = case.getJSONArray("points")
            for (j in 0 until points.length()) {
                val p = points.getJSONObject(j)
                tracker.add(GpsPoint(p.getDouble("latitude"), p.getDouble("longitude"), p.getLong("time"),
                    p.getDouble("accuracy"), if (p.has("speed")) p.getDouble("speed") else null))
            }
            assertEquals(case.getString("name"), case.getDouble("expectedMeters"), Math.round(tracker.meters * 10) / 10.0, 0.0)
        }
    }
}
