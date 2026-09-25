package com.michalkrsik.internetrestrictor.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.michalkrsik.internetrestrictor.domain.CommandResult
import com.michalkrsik.internetrestrictor.domain.PersistedState
import com.michalkrsik.internetrestrictor.domain.RestrictionEngine
import com.michalkrsik.internetrestrictor.domain.RuntimeSignals
import com.michalkrsik.internetrestrictor.ui.dashboard.DashboardScreen
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], qualifiers = "w411dp-h891dp", application = android.app.Application::class)
class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val engine = RestrictionEngine()
    private val now = 1_787_072_400_000L
    private val zone = ZoneId.of("UTC")

    @Test fun availableTimeHandlesAbsentFractionalAndUnboundedBudgetValues() {
        val initial = engine.snapshot(engine.normalize(PersistedState(), now, zone), RuntimeSignals(), now, zone)
        val snapshot = androidx.compose.runtime.mutableStateOf(initial.copy(budgetSnapshots = emptyList()))
        composeRule.setContent { RestrictorTheme(com.michalkrsik.internetrestrictor.domain.ThemePreference.LIGHT) {
            DashboardScreen(snapshot.value, {}, {}, {}, {})
        } }
        composeRule.onNodeWithText("0 min total").performScrollTo().assertExists()
        composeRule.onNodeWithText("0 min used").assertExists()
        composeRule.runOnIdle { snapshot.value = snapshot.value.copy(budgetSnapshots = listOf(initial.budgetSnapshots.first().copy(
            remainingMs = 500, percentage = 100, usedMs = 60_000, limitMinutes = 2))) }
        composeRule.onNodeWithText("<1").performScrollTo().assertExists()
        composeRule.onNodeWithText("1 min used").assertExists()
        composeRule.runOnIdle { snapshot.value = snapshot.value.copy(budgetSnapshots = snapshot.value.budgetSnapshots.map { it.copy(remainingMs = null) }) }
        composeRule.onNodeWithText("<1").assertDoesNotExist()
        composeRule.onNodeWithText("2 min total").assertExists()
    }

    @Test fun emptyTimelineDistinguishesNoActivityFromEarlierAggregateHistory() {
        val initial = engine.snapshot(engine.normalize(PersistedState(), now, zone), RuntimeSignals(), now, zone)
        val snapshot = androidx.compose.runtime.mutableStateOf(initial.copy(todayActivityEvents = emptyList()))
        composeRule.setContent { RestrictorTheme(snapshot.value.settings.theme) {
            DashboardScreen(snapshot.value, {}, {}, {}, {})
        } }
        composeRule.onNodeWithText("This week").performScrollTo().performClick()
        composeRule.onAllNodesWithText("None yet today").assertCountEquals(2)
        composeRule.runOnIdle { snapshot.value = snapshot.value.copy(todayUsageReport = snapshot.value.todayUsageReport.copy(
            exerciseSets = 2, protectedUsageMs = 120_000)) }
        composeRule.onAllNodesWithText("Earlier today").assertCountEquals(2)
    }

    @Test
    fun weeklyActivityIsTheDefaultAndDayViewIsAvailable() {
        val normalized = engine.normalize(PersistedState(), now, zone)
        val earned = (engine.earnPushupBudget(normalized, now, zone) as CommandResult.Applied).state
        val carried = (engine.recordPushupRepetitions(earned, 4, now + 1, zone) as CommandResult.Applied).state
        val snapshot = engine.snapshot(
            carried,
            RuntimeSignals(
                serviceConnected = true,
                accessibilityEnabled = true,
                storageReady = true,
            ),
            now,
            zone,
        )

        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) {
                DashboardScreen(snapshot, {}, {}, {}, {})
            }
        }

        composeRule.onNodeWithText("MAKE TIME.").assertIsDisplayed()
        composeRule.onNodeWithText("PROGRESS TO NEXT CREDIT").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("This week").performScrollTo().performClick()
        composeRule.onNodeWithText("MONDAY – SUNDAY").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("browser time today").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Exercise set").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("PUSH-UPS").performScrollTo().performClick()
        composeRule.onNodeWithText("PREVIOUS CYCLES").performScrollTo().assertIsDisplayed()
    }
}
