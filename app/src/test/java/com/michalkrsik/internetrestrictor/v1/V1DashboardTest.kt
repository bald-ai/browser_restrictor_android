package com.michalkrsik.internetrestrictor.v1

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class V1DashboardTest {
    @get:Rule val compose = createComposeRule()
    private val now = 1_790_250_000_000L
    private val baseline = RestrictionEngine().snapshot(PersistedState(), RuntimeSignals(storageReady = true), now, ZoneId.of("UTC"))
    private val snapshot = mutableStateOf(baseline)
    private val connection = mutableStateOf("Your setup is up to date")
    private val events = mutableListOf<String>()
    private var failCorrection = false
    private var failFallback = false
    private var pending: CompletableDeferred<Unit>? = null
    private fun mount() {
        compose.setContent { RestrictorTheme(ThemePreference.LIGHT) {
            V1Dashboard(snapshot.value, connection.value, { events += "conversation" }, { events += "settings" },
                { events += it.id }, { events += "health" }, { events += "retry" },
                { receipt, value -> events += "${receipt.measurement.id}:$value"; pending?.await(); if (failCorrection) error("Correction was rejected") },
                { receipt -> events += "fallback:${receipt.measurement.id}"; pending?.await(); if (failFallback) error("Offline fallback") })
        } }
    }
    private fun click(text: String) { compose.onNodeWithText(text).performScrollTo().performClick() }
    private fun receipt(gps: Boolean = false): SavedTaskReceipt {
        val task = baseline.configuration.tasks.first { if (gps) it.gpsMode == "walk" else it.metric == "repetitions" }
        return SavedTaskReceipt(TaskMeasurementReceipt("result", task.id, 1, task, 15, now - 1000, now,
            fallbackEligible = gps), dayKey = "today", budgetKey = "today", rewardEligible = true, awardedMinutes = 3, awardedCredits = 1)
    }
    private fun show(receipt: SavedTaskReceipt, modifiable: Boolean = true) {
        snapshot.value = baseline.copy(taskReceipts = listOf(receipt), modifiableTaskReceiptIds = if (modifiable) setOf(receipt.measurement.id) else emptySet())
    }

    @Test fun navigationTasksEmptyHistoryAndConnectionRecoveryDispatchTheirExactActions() {
        mount()
        click("Settings"); click("Change your app by voice"); click("Edit")
        assertEquals(listOf("settings", "conversation", "conversation"), events)
        for (task in baseline.configuration.tasks) click(task.name)
        assertEquals(baseline.configuration.tasks.map { it.id }, events.takeLast(baseline.configuration.tasks.size))
        click("View history")
        compose.onNodeWithText("Your verified tasks will appear here.").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.runOnIdle { connection.value = "Offline · retry" }
        click("Retry connection & saved recordings")
        compose.runOnIdle { connection.value = "Saved recording waiting" }
        click("Retry connection & saved recordings")
        compose.runOnIdle {
            snapshot.value = baseline.copy(configuration = baseline.configuration.copy(tasks = baseline.configuration.tasks.map { it.copy(paused = true) }), earnedBudgetRoundsToday = 99)
            connection.value = "Connected"
        }
        compose.onNodeWithText("No active tasks. Add one or unpause a task in Customize.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("YouTube unlocked").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry connection & saved recordings").assertDoesNotExist()
        assertEquals(2, events.count { it == "retry" })
    }

    @Test fun correctionRejectsInvalidInputsAndRetainsErrorUntilSuccessfulRetry() {
        show(receipt()); mount(); click("Correct measurement")
        val input = compose.onNode(hasSetTextAction())
        for (value in listOf("no", "-1", "15.5", "100001")) {
            input.performTextReplacement(value)
            compose.onNodeWithText("Save correction").assertIsNotEnabled()
        }
        input.performTextReplacement("25")
        failCorrection = true
        compose.onNodeWithText("Save correction").performClick()
        compose.onNodeWithText("Correction was rejected").assertIsDisplayed()
        assertEquals("result:25.0", events.single())
        failCorrection = false
        compose.onNodeWithText("Save correction").performClick()
        compose.onNodeWithText("Save correction").assertDoesNotExist()
        click("Correct measurement"); compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Save correction").assertDoesNotExist()
    }

    @Test fun pendingCorrectionDisablesDuplicateSaveAndCancelAndDecimalGpsValuesReachTheCallback() {
        show(receipt(gps = true)); mount(); click("Correct measurement")
        pending = CompletableDeferred()
        compose.onNode(hasSetTextAction()).performTextReplacement("20,5")
        compose.onNodeWithText("Save correction").performClick()
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        assertEquals(listOf("result:20.5"), events)
        compose.runOnIdle { pending!!.complete(Unit) }
        compose.onNodeWithText("Save correction").assertDoesNotExist()
    }

    @Test fun latestReceiptControlsHistoryExpiryAndCorrectionEligibility() {
        val latest = receipt().copy(corrected = true, minutesRewardEligible = false)
        show(latest); mount()
        compose.onNodeWithText("Browsing-time window expired; daily progress kept.").performScrollTo().assertIsDisplayed()
        click("View history")
        compose.onNodeWithText("+3 min · corrected").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        for (changed in listOf(
            latest.copy(measurement = latest.measurement.copy(correctionEligible = false)),
            latest.copy(measurement = latest.measurement.copy(manualCompletion = true)),
            latest.copy(measurement = latest.measurement.copy(value = -1)),
        )) {
            compose.runOnIdle { show(changed) }
            compose.onNodeWithText("Correct measurement").assertDoesNotExist()
        }
        compose.runOnIdle { show(latest, modifiable = false) }
        compose.onNodeWithText("Correct measurement").assertDoesNotExist()
        compose.runOnIdle { show(latest.copy(rewardEligible = false)) }
        compose.onNodeWithText("Saved to history · reward window expired").performScrollTo().assertIsDisplayed()
    }

    @Test fun fallbackFailureCanRetryAndPendingConfirmationCannotBeSubmittedTwice() {
        val receipt = receipt(gps = true)
        show(receipt); mount()
        failFallback = true
        click("I completed this task · 1 credit")
        compose.onNodeWithText("Offline fallback").performScrollTo().assertIsDisplayed()
        failFallback = false; pending = CompletableDeferred()
        click("I completed this task · 1 credit")
        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        assertEquals(listOf("fallback:result", "fallback:result"), events)
        compose.runOnIdle { pending!!.complete(Unit) }
        compose.onNodeWithText("I completed this task · 1 credit").assertIsEnabled()
        compose.runOnIdle { show(receipt.copy(measurement = receipt.measurement.copy(manualCompletion = true))) }
        compose.onNodeWithText("I completed this task · 1 credit").assertDoesNotExist()
        compose.runOnIdle { show(receipt, modifiable = false) }
        compose.onNodeWithText("I completed this task · 1 credit").assertDoesNotExist()
    }
}
