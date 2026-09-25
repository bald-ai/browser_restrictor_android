package com.michalkrsik.internetrestrictor.v1

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VideoRecordingScreenTest {
    @get:Rule val compose = createComposeRule()
    private val model = mutableStateOf(VideoScreenState(AppConfiguration().tasks.first { it.verifier == "video" }, "credits", false, "ready", "Ready fixture", 30, false))
    private val events = mutableListOf<String>()
    private fun mount() { compose.setContent { RestrictorTheme(ThemePreference.LIGHT) {
        VideoRecordingScreen(model.value, VideoScreenActions({ events += "back" }, { events += "stop" }, { events += "finish" },
            { events += "retryPreview" }, { events += "begin" }, { events += "switch" }, { events += "submit" })) { Box(it) }
    } } }
    private fun state(phase: String, preview: Boolean = false, retry: Boolean = false) { compose.runOnIdle { model.value = model.value.copy(phase = phase, preview = preview, canRetry = retry, seconds = 3) } }

    @Test fun readyCountdownRecordingAndResultDispatchOnlyTheirAvailableActions() {
        mount()
        compose.onNodeWithText("Record · up to 30 seconds").performClick()
        compose.onNodeWithText("Switch camera").performClick()
        compose.onNodeWithText("‹ Back").performClick()
        assertEquals(listOf("begin", "switch", "back"), events)
        state("countdown")
        compose.onNodeWithText("Starting in 3…").assertExists()
        compose.onNodeWithText("Record · up to 30 seconds").assertDoesNotExist()
        state("recording")
        compose.onNodeWithText("Stop & verify · 3s left").performClick()
        state("busy")
        compose.onNodeWithText("Checking your activity…").assertExists()
        state("result")
        compose.onNodeWithText("Back to your day").performClick()
        state("error", retry = true)
        compose.onNodeWithText("Retry saved recording").performClick()
        assertEquals(listOf("stop", "finish", "submit"), events.takeLast(3))
    }

    @Test fun previewRecoveryOffersUploadOrReturnAndNeverStartsAnotherCapture() {
        mount(); state("preview_waiting", preview = true)
        compose.onNodeWithText("Test recording · your setup and rewards stay unchanged").assertExists()
        compose.onNodeWithText("‹ Back to review").performClick()
        compose.onNodeWithText("Retry saved recording").performClick()
        compose.onNodeWithText("Back to review").performClick()
        compose.onNodeWithText("Record · up to 30 seconds").assertDoesNotExist()
        state("preview_interrupted", preview = true)
        compose.onNodeWithText("Back to review").performClick()
        compose.onNodeWithText("Retry saved recording").assertDoesNotExist()
        state("result", preview = true)
        compose.onNodeWithText("Back to review").performClick()
        assertEquals(listOf("back", "retryPreview", "finish", "finish", "finish"), events)
    }
}
