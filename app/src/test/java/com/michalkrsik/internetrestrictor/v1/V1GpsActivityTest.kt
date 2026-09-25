package com.michalkrsik.internetrestrictor.v1

import android.Manifest
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.michalkrsik.internetrestrictor.data.ConfigurationJson
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.fixtures.FixtureApplication
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], application = FixtureApplication::class)
class V1GpsActivityTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = RuntimeEnvironment.getApplication() as FixtureApplication
    private val task = AppConfiguration().tasks.first { it.gpsMode == "walk" }
    private val controllers = mutableListOf<ActivityController<V1GpsActivity>>()
    @Before fun setup() { V1GpsService.state.value = V1GpsState(); V1GpsService.activeAttemptId = null }
    @After fun close() {
        controllers.forEach { it.pause().stop().destroy() }
        V1GpsService.state.value = V1GpsState(); V1GpsService.activeAttemptId = null
    }
    private fun mount(preview: String? = null): V1GpsActivity {
        val intent = Intent(app, V1GpsActivity::class.java).putExtra("taskId", task.id).putExtra("previewAttemptId", preview)
        val controller = Robolectric.buildActivity(V1GpsActivity::class.java, intent).setup()
        controllers += controller
        return controller.get()
    }
    private fun call(activity: V1GpsActivity, name: String) { compose.runOnIdle { ReflectionHelpers.callInstanceMethod<Unit>(activity, name) } }
    private fun preview(id: String): JSONObject = JSONObject().put("attemptId", id).put("taskId", task.id)
        .put("preview", true).put("startedAt", app.clock.wallMs).put("configRevision", 1)
        .put("task", ConfigurationJson.taskJson(task)).also { File(app.filesDir, "v1-preview-attempts/$id.json").writeText(it.toString()) }

    @Test fun precisePermissionIsRequestedAndDenialIsVisibleBeforeRetryStartsTheSelectedService() {
        val activity = mount()
        call(activity, "requestTracking")
        val request = shadowOf(activity).lastRequestedPermission
        assertTrue(request.requestedPermissions.contains(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue(request.requestedPermissions.contains(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertEquals(android.os.Build.VERSION.SDK_INT >= 33, request.requestedPermissions.contains(Manifest.permission.POST_NOTIFICATIONS))
        compose.runOnIdle { activity.activityResultRegistry.dispatchResult(request.requestCode, mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false)) }
        compose.onNodeWithText("Precise location permission is needed", substring = true).assertExists()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
        call(activity, "requestTracking")
        val intent = shadowOf(app).nextStartedService
        assertEquals(task.id, intent.getStringExtra("taskId"))
        assertTrue(intent.component!!.className.endsWith("V1GpsService"))
        assertNull(intent.getStringExtra("previewAttemptId"))
    }

    @Test fun activeRecordingSelectsItsOwnTaskAndStopDispatchesOnlyAServiceIntent() {
        val sprint = AppConfiguration().tasks.first { it.gpsMode == "sprint" }
        V1GpsService.state.value = V1GpsState(taskId = sprint.id, recording = true, meters = 15.0)
        val activity = mount()
        compose.onNodeWithText(sprint.name).assertExists()
        compose.onNodeWithText("Stop & verify").performClick()
        assertEquals("stop", shadowOf(app).nextStartedService.action)
        compose.onNodeWithText("‹ Back to your day").performClick()
        assertTrue(activity.isFinishing)
    }

    @Test fun missingPreviewFinishesWithRecoveryGuidanceAndClaimedPreviewCannotStartTwice() {
        val missing = mount("missing")
        assertTrue(missing.isFinishing)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Start a new test"))
        preview("claimed")
        val activity = mount("claimed")
        call(activity, "startTracking")
        assertEquals("claimed", shadowOf(app).nextStartedService.getStringExtra("previewAttemptId"))
        app.container.v1.claimPreviewRecording("claimed")
        call(activity, "startTracking")
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test fun previewUploadFailureRetainsEvidenceAndReenablesRetry() {
        val spec = preview("pending")
        app.container.v1.claimPreviewRecording("pending")
        app.container.v1.persistGps(spec, org.json.JSONArray(), app.clock.wallMs, true)
        mount("pending")
        compose.onNodeWithText("Retry saved recording").performClick()
        compose.onNodeWithText("Retry saved recording").assertIsEnabled()
        assertTrue(File(app.filesDir, "v1-evidence/pending.json").exists())
        compose.onNodeWithText("backend is not configured", substring = true).assertExists()
    }
}
