package com.michalkrsik.internetrestrictor.ui

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.components.ExerciseCorrectionForm
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** In-memory component host with injected persistence; no application or device is started. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ExerciseCorrectionUiTest {
    @get:Rule val rule = createComposeRule()
    private val receipt = ExerciseReceipt("receipt", "2026-09-24", 1_790_250_000_000L,
        ExerciseId.PUSH_UP, 1, pointsBefore = 0, awardedSets = 0, legacyEveningResetApplied = false)

    @Test fun exactCountsAreValidatedAndPersistenceReceivesTheSelectedMeasurement() {
        var saved = 0
        var submitted: List<Any>? = null
        rule.setContent { RestrictorTheme(ThemePreference.DARK) {
            ExerciseCorrectionForm(receipt, {}, { saved++ }) { id, exercise, value, flag ->
                submitted = listOf(id, exercise, value, flag)
                RewardSaveResult.Saved(RewardCommit(1, 0, receipt.dayKey))
            }
        } }
        rule.onNodeWithText("+3").performScrollTo().performClick()
        rule.onNodeWithText("+1").performScrollTo().performClick()
        rule.onNodeWithText("5 repetitions").assertExists()
        rule.onNodeWithText("Enter exact count").performScrollTo().performClick()
        for (invalid in listOf("-1", "10000", "abc", "")) {
            rule.onNodeWithText("Exact count").performScrollTo().performTextReplacement(invalid)
            rule.onNodeWithText("Save correction").assertIsNotEnabled()
        }
        rule.onNodeWithText("Exact count").performScrollTo().performTextReplacement("0")
        rule.onNodeWithText("Save correction").performClick()
        rule.runOnIdle {
            assertEquals(listOf("receipt", ExerciseId.PUSH_UP, 0L, false), submitted)
            assertEquals(1, saved)
            assertEquals(1L, receipt.originalValue)
        }
    }

    @Test fun rejectedAndStorageFailuresStayOpenAndAllowRetryOrFlag() {
        var attempts = 0
        var saved = 0
        var lastFlag = false
        rule.setContent { RestrictorTheme(ThemePreference.LIGHT) {
            ExerciseCorrectionForm(receipt, {}, { saved++ }) { _, _, _, flag ->
                lastFlag = flag
                when (++attempts) {
                    1 -> RewardSaveResult.Rejected("Receipt expired")
                    2 -> RewardSaveResult.StorageFailure
                    else -> RewardSaveResult.Saved(RewardCommit(0, 0, receipt.dayKey))
                }
            }
        } }
        rule.onNodeWithText("Save correction").performClick()
        rule.onNodeWithText("Receipt expired").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Save correction").performClick()
        rule.onNodeWithText("Could not save. Your previous result is unchanged. Try again.").performScrollTo().assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, saved) }
        rule.onNodeWithText("Only flag as incorrect").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(3, attempts); assertEquals(1, saved); assertTrue(lastFlag) }
    }

    @Test fun pendingSaveDisablesDuplicateSubmissionAndCancelUntilItCompletes() {
        val result = CompletableDeferred<RewardSaveResult>()
        var calls = 0
        var cancelled = 0
        var saved = 0
        rule.setContent { RestrictorTheme(ThemePreference.DARK) {
            ExerciseCorrectionForm(receipt, { cancelled++ }, { saved++ }) { _, _, _, _ ->
                calls++
                result.await()
            }
        } }
        rule.onNodeWithText("Save correction").performClick()
        rule.onNodeWithText("Saving…").assertIsNotEnabled()
        rule.onNodeWithText("Cancel").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(1, calls); assertEquals(0, cancelled); result.complete(RewardSaveResult.StorageFailure) }
        rule.onNodeWithText("Save correction").assertIsEnabled()
        rule.onNodeWithText("Cancel").performClick()
        rule.runOnIdle { assertEquals(1, cancelled); assertEquals(0, saved) }
    }
}
