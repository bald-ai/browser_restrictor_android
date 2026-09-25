package com.michalkrsik.internetrestrictor.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.components.*
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ProgressComponentsTest {
    @get:Rule val compose = createComposeRule()

    @Test fun exerciseProgressClampsValuesAndHelpOpensAndClosesInline() {
        val sixths = mutableStateOf(-1L)
        compose.setContent { RestrictorTheme(ThemePreference.LIGHT) { ExercisePointsProgress(sixths.value) } }
        val progress = compose.onNodeWithContentDescription("Exercise points toward a 3-minute boost")
        for ((value, expected) in listOf(-1L to 0f, 0L to 0f, 6L to 1f, 15L to 2.5f, 30L to 5f, 31L to 5f)) {
            compose.runOnIdle { sixths.value = value }
            progress.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(expected, 0f..5f)))
        }
        compose.onNodeWithContentDescription("How exercise points work").performClick()
        compose.onNodeWithText("Exercise points").assertIsDisplayed()
        compose.onNodeWithText("Got it").performClick()
        compose.onNodeWithText("Exercise points").assertDoesNotExist()
        compose.onNodeWithContentDescription("How exercise points work").performClick()
        compose.onNodeWithContentDescription("How exercise points work").performClick()
        compose.onNodeWithText("Exercise points").assertDoesNotExist()
    }

    @Test fun disabledForcedExhaustedWarningAndUnboundedBudgetsHaveAccurateAccessibleCopy() {
        val budget = mutableStateOf(BudgetSnapshot(BudgetPeriod.DAILY, true, 60_000, 0, 60_000, 0, false))
        val force = mutableStateOf(false)
        val label = mutableStateOf<String?>(null)
        compose.setContent { RestrictorTheme(ThemePreference.DARK) { BudgetBar(budget.value, forceExhausted = force.value, label = label.value) } }
        compose.onNodeWithContentDescription("Daily budget, 0 percent used, 1 min left").assertExists()
        compose.onNodeWithText("1m left").assertExists()
        compose.runOnIdle { budget.value = budget.value.copy(percentage = 80, remainingMs = 12_000, usedMs = 48_000) }
        compose.onNodeWithContentDescription("Daily budget, 80 percent used, less than a minute left").assertExists()
        compose.runOnIdle { force.value = true; label.value = "Current day" }
        compose.onNodeWithText("Used").assertExists(); compose.onNodeWithText("Current day").assertExists()
        compose.onNodeWithContentDescription("Daily budget, 100 percent used, less than a minute left").assertExists()
        compose.runOnIdle { force.value = false; budget.value = budget.value.copy(exhausted = true, percentage = 100, remainingMs = 0) }
        compose.onNodeWithText("Used").assertExists()
        compose.runOnIdle { budget.value = budget.value.copy(exhausted = false, remainingMs = null, percentage = -5) }
        compose.onNodeWithText("<1m left").assertExists()
        compose.runOnIdle { budget.value = budget.value.copy(enabled = false) }
        compose.onNodeWithText("Current day").assertDoesNotExist()
    }

    @Test fun formattingBoundariesAndExerciseMetricsRetainTheirPublicMeaning() {
        val examples = listOf(-1L to "less than a minute", 59_999L to "less than a minute", 60_000L to "1 min", 60_001L to "2 min", 3_600_000L to "1 hr", 3_660_000L to "1 hr 1 min")
        for ((value, formatted) in examples) assertEquals(formatted, formatLongTime(value))
        assertEquals("0m", formatReportTime(-1)); assertEquals("<1m", formatReportTime(1))
        assertEquals("daily", BudgetPeriod.DAILY.lowerLabel()); assertEquals("Today", BudgetPeriod.DAILY.friendlyLabel())
        for (id in listOf(ExerciseId.PUSH_UP, ExerciseId.OUTDOOR_LAT_PULLDOWN, ExerciseId.SIT_UP)) assertEquals(ExerciseMetric.REPETITIONS, ExercisePolicies.requiresMetric(id))
        assertEquals(ExerciseMetric.HOLD_SECONDS, ExercisePolicies.requiresMetric(ExerciseId.PLANK))
        assertEquals(ExerciseMetric.NONE, ExercisePolicies.requiresMetric(ExerciseId.UNSUPPORTED))
    }
}
