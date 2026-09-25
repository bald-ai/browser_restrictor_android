package com.michalkrsik.internetrestrictor.ui

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.settings.SettingsScreen
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SettingsLegacyPresetTest {
    @get:Rule val compose = createComposeRule()
    @Test fun presetsRejectInvalidInputAndSaveAllThreeValidBoundaries() {
        val engine = RestrictionEngine()
        val now = 1_790_250_000_000L
        val snapshot = engine.snapshot(engine.normalize(PersistedState(), now, ZoneId.of("UTC")), RuntimeSignals(), now)
        var saved: RestrictorSettings? = null
        var theme by mutableStateOf(ThemePreference.LIGHT)
        compose.setContent { RestrictorTheme(theme) {
            SettingsScreen(snapshot.copy(settings = snapshot.settings.copy(theme = theme)), {}, { theme = it },
                { saved = it }, {}, {}, {}, {}, {}, {}, configurableTasks = false)
        } }
        val first = compose.onNodeWithContentDescription("Session preset 1 in minutes")
        for (invalid in listOf("", "0", "121", "1.5", "abc")) {
            first.performScrollTo().performTextReplacement(invalid)
            compose.onNodeWithText("SAVE CHANGES").performScrollTo().assertIsNotEnabled()
            compose.runOnIdle { assertNull(saved) }
        }
        first.performScrollTo().performTextReplacement("1")
        compose.onNodeWithContentDescription("Session preset 2 in minutes").performTextReplacement("120")
        compose.onNodeWithContentDescription("Session preset 3 in minutes").performTextReplacement("7")
        compose.onNodeWithText("SAVE CHANGES").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(1L, 120L, 7L), saved!!.sessionMinutes); assertTrue(saved!!.delayEnabled) }
        compose.onNodeWithContentDescription("Dark appearance").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ThemePreference.DARK, theme) }
    }
}
