package com.michalkrsik.internetrestrictor.v1

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp", application = Application::class)
class GpsRecordingScreenTest {
    @get:Rule val rule = createComposeRule()
    private val task = STARTER_TASKS.first { it.verifier == "gps" && it.gpsMode == "walk" }
    private fun state(gps: V1GpsState = V1GpsState()) = GpsScreenState(task, "credits", gps)
    private val events = mutableListOf<String>()
    private val actions = GpsScreenActions({ events += "back" }, { events += "start" },
        { events += "stop" }, { events += "fallback" }, { events += "retry" })

    @Test fun freshPreviewOffersStartBeforeAcquisitionAndInterruptedPreviewOffersOnlyReturn() {
        val screen = mutableStateOf(state().copy(preview = true))
        rule.setContent { RestrictorTheme(ThemePreference.DARK) { GpsRecordingScreen(screen.value, actions) } }
        rule.onNodeWithText("Start recording").performClick()
        rule.runOnIdle { screen.value = screen.value.copy(previewStatus = PreviewRecordingStatus.INTERRUPTED) }
        rule.onNodeWithText("Start recording").assertDoesNotExist()
        rule.onNodeWithText("Retry saved recording").assertDoesNotExist()
        rule.onNodeWithText("Back to review").performClick()
        rule.runOnIdle { assertEquals(listOf("start", "back"), events) }
    }

    @Test fun readyRecordingAndSubmittingHaveMutuallyExclusiveActions() {
        val screen = mutableStateOf(state())
        rule.setContent { RestrictorTheme(ThemePreference.DARK) { GpsRecordingScreen(screen.value, actions) } }
        rule.onNodeWithText("${task.unitsPerCredit} meters = 1 credit").assertIsDisplayed()
        rule.onNodeWithText("Start recording").performClick()
        rule.runOnIdle { screen.value = state(V1GpsState(acquiring = true)) }
        rule.onNodeWithText("Start recording").assertDoesNotExist()
        rule.onNodeWithText("Stop & verify").performClick()
        rule.runOnIdle { screen.value = state(V1GpsState(recording = true, meters = 123.9, elapsedSeconds = 65, accuracy = 4.9f)) }
        rule.onNodeWithText("123 m").assertIsDisplayed()
        rule.onNodeWithText("1:05 elapsed · GPS ±4m").assertIsDisplayed()
        rule.onNodeWithText("Stop & verify").performClick()
        rule.runOnIdle { screen.value = state(V1GpsState(submitting = true, status = "Checking evidence")) }
        rule.onNodeWithText("Start recording").assertDoesNotExist()
        rule.onNodeWithText("Stop & verify").assertDoesNotExist()
        rule.onNodeWithText("Checking evidence").assertIsDisplayed()
        rule.onNodeWithText("‹ Back to your day").performClick()
        rule.runOnIdle { assertEquals(listOf("start", "stop", "stop", "back"), events) }
    }

    @Test fun recoveredPreviewCanRetryOnlyWhileWaitingAndNeverOffersNewRecording() {
        val screen = mutableStateOf(state().copy(preview = true, previewStatus = PreviewRecordingStatus.WAITING_UPLOAD))
        rule.setContent { RestrictorTheme(ThemePreference.LIGHT) { GpsRecordingScreen(screen.value, actions) } }
        rule.onNodeWithText("Test recording · your setup and rewards stay unchanged").assertIsDisplayed()
        rule.onNodeWithText("Retry saved recording").performClick()
        rule.runOnIdle { screen.value = screen.value.copy(retrying = true) }
        rule.onNodeWithText("Checking recording…").assertIsNotEnabled()
        rule.runOnIdle { screen.value = screen.value.copy(previewStatus = PreviewRecordingStatus.COMPLETE,
            previewResult = JSONObject().put("measuredUnits", 42.8).put("preview", true)) }
        rule.onNodeWithText("42 m").assertIsDisplayed()
        rule.onNodeWithText("Test checked. No rewards granted.").assertIsDisplayed()
        rule.onNodeWithText("Retry saved recording").assertDoesNotExist()
        rule.onNodeWithText("Start recording").assertDoesNotExist()
        rule.onNodeWithText("Back to review").performClick()
        rule.runOnIdle { assertEquals(listOf("retry", "back"), events) }
    }

    @Test fun fallbackAndPermissionErrorsAreVisibleWithoutStartingAnyRecording() {
        val screen = mutableStateOf(state().copy(permissionError = true, canFallback = true))
        rule.setContent { RestrictorTheme(ThemePreference.DARK) { GpsRecordingScreen(screen.value, actions) } }
        rule.onNodeWithText("Precise location permission is needed to verify distance.").assertIsDisplayed()
        rule.onNodeWithText("I completed this task · 1 credit").performClick()
        rule.runOnIdle { screen.value = screen.value.copy(fallbackBusy = true, error = "Offline") }
        rule.onNodeWithText("Saving…").assertIsNotEnabled()
        rule.onNodeWithText("Offline").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf("fallback"), events) }
    }

    @Test fun previewStatusRespectsActiveCaptureAndRecoveryBoundaries() {
        for (status in PreviewRecordingStatus.entries) {
            val base = state().copy(preview = true, previewStatus = status)
            assertTrue(base.recovered)
            for (active in listOf(V1GpsState(recording = true), V1GpsState(acquiring = true), V1GpsState(submitting = true))) {
                assertFalse(base.copy(gps = active).recovered)
                assertEquals(active.status, base.copy(gps = active).status)
            }
        }
        for (status in listOf(PreviewRecordingStatus.MISSING, PreviewRecordingStatus.INTERRUPTED)) {
            assertEquals("This test recording was interrupted. Return to the review card to start a new test.",
                state().copy(preview = true, previewStatus = status).status)
        }
        assertEquals(0, state().copy(preview = true, previewStatus = PreviewRecordingStatus.COMPLETE).displayedMeters)
        assertEquals("Ready to record. Keep your phone with you.", state().copy(preview = true).status)
    }

    @Test fun fallbackNeedsAnEligibleUncompletedReceiptInTheCurrentModifiableSet() {
        val measurement = TaskMeasurementReceipt("gps", task.id, 0, task, 0, 1, 2, fallbackEligible = true)
        val receipt = SavedTaskReceipt(measurement, 0, "2026-09-24", "ledger", rewardEligible = true)
        assertTrue(canConfirmGpsFallback(false, V1GpsState(), receipt, setOf("gps")))
        assertFalse(canConfirmGpsFallback(true, V1GpsState(), receipt, setOf("gps")))
        assertFalse(canConfirmGpsFallback(false, V1GpsState(recording = true), receipt, setOf("gps")))
        assertFalse(canConfirmGpsFallback(false, V1GpsState(acquiring = true), receipt, setOf("gps")))
        assertFalse(canConfirmGpsFallback(false, V1GpsState(), null, setOf("gps")))
        assertFalse(canConfirmGpsFallback(false, V1GpsState(), receipt, emptySet()))
        assertFalse(canConfirmGpsFallback(false, V1GpsState(), receipt.copy(measurement = measurement.copy(fallbackEligible = false)), setOf("gps")))
        assertFalse(canConfirmGpsFallback(false, V1GpsState(), receipt.copy(measurement = measurement.copy(manualCompletion = true)), setOf("gps")))
    }
}
