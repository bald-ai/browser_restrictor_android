package com.michalkrsik.internetrestrictor.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.domain.RestrictorSettings
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], qualifiers = "w411dp-h891dp", application = android.app.Application::class)
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
        composeRule.onNodeWithText("Browser protection").performScrollTo().performClick()
        composeRule.onNodeWithText("Accessibility").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Done").performClick()
        composeRule.onNodeWithContentDescription("More settings and diagnostics").performScrollTo().performClick()
        composeRule.onNodeWithText("Clear report history").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Export diagnostics").performScrollTo().assertIsDisplayed()
    }
    @Test
    fun configurationOpensTheEditorAndAppearanceChangesImmediately() {
        val engine = RestrictionEngine()
        val now = 1_787_072_400_000L
        val zone = ZoneId.of("UTC")
        val snapshot = engine.snapshot(engine.normalize(PersistedState(), now, zone), RuntimeSignals(storageReady = true), now, zone)
        var appearance by mutableStateOf(ThemePreference.LIGHT)
        var customized = 0
        composeRule.setContent {
            RestrictorTheme(appearance) {
                SettingsScreen(snapshot.copy(settings = snapshot.settings.copy(theme = appearance)), {}, { appearance = it }, {}, {}, {}, {}, {}, {}, {}, { customized++ })
            }
        }
        composeRule.onNodeWithText("CUSTOMIZE TASKS & RULES").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, customized) }
        composeRule.onNodeWithContentDescription("Dark appearance").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(ThemePreference.DARK, appearance) }
        composeRule.onNodeWithContentDescription("Dark appearance").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(ThemePreference.LIGHT, appearance) }
        composeRule.onAllNodesWithText("SAVE CHANGES").assertCountEquals(0)
    }
}
