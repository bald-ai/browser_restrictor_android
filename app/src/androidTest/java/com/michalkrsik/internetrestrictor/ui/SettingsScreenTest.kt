package com.michalkrsik.internetrestrictor.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.michalkrsik.internetrestrictor.domain.PersistedState
import com.michalkrsik.internetrestrictor.domain.RestrictionEngine
import com.michalkrsik.internetrestrictor.domain.RuntimeSignals
import com.michalkrsik.internetrestrictor.ui.settings.SettingsScreen
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun informationalAndDeveloperSectionsStartCollapsed() {
        val engine = RestrictionEngine()
        val now = 1_787_072_400_000L
        val state = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val snapshot = engine.snapshot(
            state,
            RuntimeSignals(
                accessibilityEnabled = true,
                serviceConnected = true,
                storageReady = true,
            ),
            now,
            ZoneId.of("UTC"),
        )

        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) {
                SettingsScreen(snapshot, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }

        composeRule.onAllNodesWithText("Budgets").assertCountEquals(0)
        composeRule.onAllNodesWithText("Require wait before browser access").assertCountEquals(0)
        composeRule.onAllNodesWithText("Clear report history").assertCountEquals(0)
        composeRule.onAllNodesWithText("Accessibility").assertCountEquals(0)
        composeRule.onAllNodesWithText("Show").assertCountEquals(2)

        composeRule.onNodeWithText("System access").performScrollTo().performClick()
        composeRule.onNodeWithText("Accessibility").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Hide").assertCountEquals(1)

        composeRule.onNodeWithText("Developer").performScrollTo().performClick()
        composeRule.onNodeWithText("Clear report history").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Export diagnostics").performScrollTo().assertIsDisplayed()
    }
}
