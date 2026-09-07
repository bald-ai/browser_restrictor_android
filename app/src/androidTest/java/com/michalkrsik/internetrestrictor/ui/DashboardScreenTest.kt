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

class DashboardScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val engine = RestrictionEngine()
    private val now = 1_787_072_400_000L
    private val zone = ZoneId.of("UTC")

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
        composeRule.onNodeWithContentDescription("Exercise points toward a 3-minute boost").assertIsDisplayed()
        composeRule.onNodeWithText("EXPIRES IN").assertIsDisplayed()
        composeRule.onNodeWithText("This week").performScrollTo().performClick()
        composeRule.onNodeWithText("MONDAY – SUNDAY").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("browser time today").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Exercise set").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithText("PUSH-UPS").performScrollTo().performClick()
        composeRule.onNodeWithText("PREVIOUS CYCLES").performScrollTo().assertIsDisplayed()
    }
}
