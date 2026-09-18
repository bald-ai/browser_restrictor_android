package com.michalkrsik.internetrestrictor.v1

import androidx.test.platform.app.InstrumentationRegistry
import com.michalkrsik.internetrestrictor.BuildConfig
import com.michalkrsik.internetrestrictor.appContainer
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.UUID

/** Opt-in native/backend integration. Uses a labelled synthetic GPS fixture, with no model cost. */
class V1PreviewDeviceTest {
    @Test fun draftGpsMeasurementNeverChangesRewardsOrConfiguration() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("v1PreviewSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(BuildConfig.IS_V1 && context.packageName.endsWith(".v1dev"))
        val coordinator = context.appContainer.v1
        assertTrue(coordinator.sync())
        val before = context.appContainer.repository.snapshot.value
        val envelope = coordinator.api.request("/config")
        val taskId = "preview_test_" + UUID.randomUUID().toString().take(8)
        val config = JSONObject(envelope.getJSONObject("config").toString())
        config.getJSONArray("tasks").put(JSONObject().put("id", taskId).put("name", "Draft GPS integration test")
            .put("verifier", "gps").put("metric", "meters").put("gpsMode", "walk").put("paused", false)
            .put("unitsPerCredit", 2).put("correctionMinimum", 1).put("instructions", "Measure a walking route using supported GPS checks."))
        val staged = coordinator.api.request("/proposals", JSONObject().put("config", config)
            .put("expectedRevision", envelope.getLong("revision")).put("title", "Synthetic draft recording integration test"))
        val proposal = staged.optJSONObject("proposal") ?: staged
        val report = JSONObject().put("evidence", "Synthetic GPS fixture sent through native coordinator and real backend")
        try {
            val preview = coordinator.newPreviewAttempt(proposal.getString("id"), proposal.getInt("version"), taskId)
            assertTrue(preview.getBoolean("preview"))
            val started = preview.getLong("startedAt")
            val points = JSONArray()
            repeat(9) { index -> points.put(JSONObject().put("latitude", 50.0).put("longitude", 14.0 + index * 0.00006)
                .put("time", started + index * 2_000L).put("accuracy", 1.0).put("speed", 2.15)) }
            coordinator.persistGps(preview, points, started + 16_000L, true)
            val result = coordinator.finishGps(preview.getString("attemptId"))
            assertTrue("GPS evaluator measured the test route", result.getDouble("measuredUnits") > 1.0)
            assertTrue(result.getBoolean("preview"))
            assertFalse(result.getBoolean("rewardEligible"))
            val after = context.appContainer.repository.snapshot.value
            assertEquals(before.configuration, after.configuration)
            assertEquals(before.earnedBudgetRoundsToday, after.earnedBudgetRoundsToday)
            assertEquals(before.taskCreditProgress, after.taskCreditProgress, 0.0)
            assertEquals(before.taskReceipts, after.taskReceipts)
            val server = coordinator.api.request("/state")
            assertEquals(envelope.getLong("revision"), server.getLong("revision"))
            assertFalse(server.getJSONObject("config").getJSONArray("tasks").objects().any { it.getString("id") == taskId })
            assertEquals("pending", server.getJSONArray("proposals").objects().first { it.getString("id") == proposal.getString("id") }.getString("status"))
            assertTrue(server.getJSONArray("previewResults").objects().any { it.getString("attemptId") == preview.getString("attemptId") })
            report.put("receipt", result).put("nativeRewardsUnchanged", true).put("configurationStillPending", true)
        } finally {
            coordinator.api.request("/cancel", JSONObject().put("proposalId", proposal.getString("id")))
            File(context.filesDir, "v1-preview-device-result.json").writeText(report.toString(2))
        }
    }

    private fun JSONArray.objects() = List(length()) { getJSONObject(it) }
}
