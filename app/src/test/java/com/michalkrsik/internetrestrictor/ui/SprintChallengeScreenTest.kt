package com.michalkrsik.internetrestrictor.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.sprint.*
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp", application = Application::class)
class SprintChallengeScreenTest {
    @get:Rule val rule = createComposeRule()
    private val events = mutableListOf<String>()
    private val actions = SprintScreenActions({ events += "close" }, { events += "permissions" },
        { events += "location" }, { events += "start:$it" }, { events += "stop" }, { events += "manual" },
        { events += "retry" }, { youtube, unlocked, earned -> events += "continue:$youtube:$unlocked:$earned" },
        { events += "again:$it" })
    private val ready = SprintScreenState(SprintPhase.ACQUIRING_GPS, null, true, true, true, false)
    private val usable = SprintLiveState(isGpsUsable = true, lastAccuracyMeters = 5f, lastFixElapsedRealtimeMs = 100)
    private val result = SprintEvaluationResult(125, 17.5, 1, 5, true, 140.0, 1)

    @Test fun startRequiresPermissionEnabledProviderAndUsableFreshFix() {
        val model = mutableStateOf(ready.copy(hasFineLocation = false))
        val live = mutableStateOf(usable)
        rule.setContent { RestrictorTheme(ThemePreference.DARK) { SprintChallengeScreen(model.value, actions, 12, 0, live.value) } }
        rule.onNodeWithText("12m carried over from previous sprint").assertIsDisplayed()
        rule.onNodeWithText("Waiting for GPS…").assertIsNotEnabled()
        rule.onNodeWithText("Grant permission").performClick()
        rule.runOnIdle { model.value = ready.copy(gpsProviderEnabled = false) }
        rule.onNodeWithText("Open Location Settings").performClick()
        rule.onNodeWithText("Waiting for GPS…").assertIsNotEnabled()
        rule.runOnIdle { model.value = ready.copy(fixFresh = false) }
        rule.onNodeWithText("Acquiring precise GPS… Stale fix, waiting for update…").assertIsDisplayed()
        rule.runOnIdle { model.value = ready; live.value = usable.copy(isGpsUsable = false, lastAccuracyMeters = 30f) }
        rule.onNodeWithText("Acquiring precise GPS… Accuracy: 30m (need ≤25m)").assertIsDisplayed()
        rule.runOnIdle { live.value = SprintLiveState.INITIAL }
        rule.onNodeWithText("Acquiring precise GPS… Waiting for satellite fix…").assertIsDisplayed()
        rule.runOnIdle { live.value = usable }
        rule.onNodeWithText("GPS ready · Accuracy: 5m").assertIsDisplayed()
        rule.onNodeWithText("Start sprint").performClick()
        rule.onNodeWithText("Close").performClick()
        rule.runOnIdle { assertEquals(listOf("permissions", "location", "start:12", "close"), events) }
    }

    @Test fun recordingShowsQualifiedProgressAndStopAlwaysRemainsAvailable() {
        val live = mutableStateOf(usable.copy(totalDistanceWithRemainderMeters = 50, currentSpeedKmh = 12f))
        rule.setContent { RestrictorTheme(ThemePreference.LIGHT) { SprintChallengeScreen(ready.copy(phase = SprintPhase.RECORDING), actions, 0, 0, live.value) } }
        rule.onNodeWithText("Sprint in progress").assertIsDisplayed()
        rule.onNodeWithText("50m").assertIsDisplayed()
        rule.onNodeWithText("12.0 km/h").assertIsDisplayed()
        rule.onNodeWithText("STOP").performClick()
        rule.runOnIdle { live.value = live.value.copy(totalDistanceWithRemainderMeters = 150, currentSpeedKmh = 18f, isQualified = true) }
        rule.onNodeWithText("Sprint complete — tap Stop").assertIsDisplayed()
        rule.onNodeWithText("150m").assertIsDisplayed()
        rule.onNodeWithText("STOP").performClick()
        rule.runOnIdle { assertEquals(listOf("stop", "stop"), events) }
    }

    @Test fun savingAndRetryKeepNavigationAndAwardClaimsConsistent() {
        val saved = SprintSaveState("recording", result, isSaving = true)
        val model = mutableStateOf(ready.copy(phase = SprintPhase.RESULT, saveState = saved, returningToYoutube = true))
        rule.setContent { RestrictorTheme(ThemePreference.DARK) { SprintChallengeScreen(model.value, actions, 5, 4, usable) } }
        rule.onNodeWithText("Saving results…").assertIsNotEnabled()
        rule.onNodeWithText("Close").assertIsNotEnabled()
        rule.onNodeWithText("Sprint again").assertIsNotEnabled()
        rule.onNodeWithText("125m measured. Progress has not been saved.").assertIsDisplayed()
        rule.runOnIdle { model.value = model.value.copy(saveState = saved.copy(isSaving = false, canRetry = true, errorMessage = "Disk full")) }
        rule.onNodeWithText("Disk full").assertIsDisplayed()
        rule.onNodeWithText("Retry saving").performClick()
        rule.runOnIdle { model.value = model.value.copy(saveState = saved.copy(isSaving = false, saveSucceeded = true, newlyEarnedSets = 1, savedRemainder = 5)) }
        rule.onNodeWithText("+1 set (+3 min)").assertIsDisplayed()
        rule.onNodeWithText("5m carried toward next set").assertIsDisplayed()
        rule.onNodeWithText("YouTube unlocked until 04:00").assertIsDisplayed()
        rule.onNodeWithText("Retry saving").assertDoesNotExist()
        rule.onNodeWithText("Continue").performClick()
        rule.onNodeWithText("Sprint again").performClick()
        rule.runOnIdle { assertEquals(listOf("retry", "continue:true:true:1", "again:5"), events) }
    }

    @Test fun unreliableRecordingAllowsManualConfirmationButNoDuplicateConfirmationAfterSave() {
        val saved = SprintSaveState("recording", result.copy(isGpsReliable = false, qualifyingDistanceMeters = 0))
        val model = mutableStateOf(ready.copy(phase = SprintPhase.RESULT, saveState = saved, returningToYoutube = true))
        rule.setContent { RestrictorTheme(ThemePreference.LIGHT) { SprintChallengeScreen(model.value, actions, 0, 2, usable) } }
        rule.onNodeWithText("GPS signal was unreliable during recording.").assertIsDisplayed()
        rule.onNodeWithText("2/4 toward YouTube unlock").assertIsDisplayed()
        rule.onNodeWithText("Confirm completed sprint (+1 set)").performClick()
        rule.runOnIdle { model.value = model.value.copy(saveState = saved.copy(isSaving = true)) }
        rule.onNodeWithText("Saving…").assertIsNotEnabled()
        rule.runOnIdle { model.value = model.value.copy(saveState = saved.copy(saveSucceeded = true, manualConfirmed = true)) }
        rule.onNodeWithText("+1 set awarded").assertIsDisplayed()
        rule.onNodeWithText("Confirm completed sprint (+1 set)").assertDoesNotExist()
        rule.runOnIdle { assertEquals(listOf("manual"), events) }
    }

    @Test fun emptyResultAndPartialSavedResultDoNotInventAnAward() {
        val model = mutableStateOf(ready.copy(phase = SprintPhase.RESULT))
        rule.setContent { RestrictorTheme(ThemePreference.DARK) { SprintChallengeScreen(model.value, actions, 0, 0, usable) } }
        rule.onNodeWithText("Sprint Results").assertIsDisplayed()
        rule.onNodeWithText("DISTANCE").assertDoesNotExist()
        rule.onNodeWithText("Continue").performClick()
        rule.runOnIdle { model.value = model.value.copy(saveState = SprintSaveState("partial", result, saveSucceeded = true, savedRemainder = 30)) }
        rule.onNodeWithText("30m carried over. Need 90m more for next set.").assertIsDisplayed()
        rule.runOnIdle { model.value = model.value.copy(saveState = model.value.saveState!!.copy(newlyEarnedSets = 2, savedRemainder = null)) }
        rule.onNodeWithText("+2 sets (+6 min)").assertIsDisplayed()
        rule.runOnIdle { assertEquals(listOf("continue:false:false:0"), events) }
    }
}
