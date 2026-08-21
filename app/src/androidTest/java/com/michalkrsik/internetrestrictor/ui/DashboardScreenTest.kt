package com.michalkrsik.internetrestrictor.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
        val snapshot = engine.snapshot(
            earned,
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

        composeRule.onNodeWithText("Your week.").assertIsDisplayed()
        composeRule.onNodeWithText("MONDAY – SUNDAY").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Open settings").assertIsDisplayed()

        composeRule.onNodeWithText("Day").performClick()
        composeRule.onNodeWithText("Today.").assertIsDisplayed()
        composeRule.onNodeWithText("browser time today").assertIsDisplayed()
        composeRule.onAllNodesWithText("1 set").assertCountEquals(2)
    }
}
