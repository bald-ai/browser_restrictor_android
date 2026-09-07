package com.michalkrsik.internetrestrictor.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.michalkrsik.internetrestrictor.domain.CommandResult
import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.PersistedState
import com.michalkrsik.internetrestrictor.domain.ProtectedTarget
import com.michalkrsik.internetrestrictor.domain.RestrictionEngine
import com.michalkrsik.internetrestrictor.domain.RuntimeSignals
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.ui.dashboard.DashboardScreen
import com.michalkrsik.internetrestrictor.ui.gate.GateActions
import com.michalkrsik.internetrestrictor.ui.gate.GateScreen
import com.michalkrsik.internetrestrictor.ui.settings.SettingsScreen
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import java.io.File
import java.time.ZoneId
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Checks the shared appearance, retained points help, and action priority on real Android. */
class PulseAppearanceTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun dayAndNightPreservePointsHelpAndExercisePriority() {
        val engine = RestrictionEngine()
        val now = 1_787_072_400_000L
        val zone = ZoneId.of("UTC")
        var state = engine.normalize(PersistedState(), now, zone)
        repeat(8) { state = (engine.earnPushupBudget(state, now, zone) as CommandResult.Applied).state }
        val snapshot = engine.snapshot(
            state,
            RuntimeSignals(
                foregroundClass = ForegroundClass.BROWSER,
                activeTarget = ProtectedTarget.BROWSER_POOL,
                gateFocused = true,
                serviceConnected = true,
                accessibilityEnabled = true,
                storageReady = true,
                gateSessionId = "pulse-test",
            ),
            now,
            zone,
        )
        var appearance by mutableStateOf(ThemePreference.DARK)
        var screen by mutableStateOf("dashboard")
        composeRule.setContent {
            RestrictorTheme(appearance) {
                val background = MaterialTheme.colorScheme.background
                SideEffect {
                    val window = composeRule.activity.window
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = appearance == ThemePreference.LIGHT
                        isAppearanceLightNavigationBars = appearance == ThemePreference.LIGHT
                    }
                    @Suppress("DEPRECATION")
                    window.navigationBarColor = background.toArgb()
                }
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val themed = snapshot.copy(settings = snapshot.settings.copy(theme = appearance))
                    when (screen) {
                        "dashboard" -> DashboardScreen(themed, {}, {}, {}, {})
                        "settings" -> SettingsScreen(themed, {}, { appearance = it }, {}, {}, {}, {}, {}, {}, {})
                        else -> GateScreen(themed, GateActions({}, {}, {}, {}, {}))
                    }
                }
            }
        }
        for (theme in listOf(ThemePreference.DARK, ThemePreference.LIGHT)) {
            composeRule.runOnIdle { appearance = theme; screen = "dashboard" }
            composeRule.onNodeWithText("AVAILABLE TO BROWSE").assertIsDisplayed()
            capture("${theme.name.lowercase()}-dashboard")
            composeRule.onNodeWithContentDescription("How exercise points work").performScrollTo().performClick()
            composeRule.onNodeWithText("Exercise points").assertIsDisplayed()
            composeRule.onNodeWithText("Got it").performClick()
            composeRule.runOnIdle { screen = "settings" }
            composeRule.onNodeWithText("Dark appearance").assertIsDisplayed()
            capture("${theme.name.lowercase()}-settings")
            composeRule.runOnIdle { screen = "gate" }
            composeRule.onNodeWithText("START EXERCISE").performScrollTo().assertIsDisplayed()
            val exercise = composeRule.onNode(hasText("START EXERCISE") and hasClickAction()).fetchSemanticsNode().boundsInRoot.height
            composeRule.onNodeWithText("CONTINUE FOR 5 MINUTES").performScrollTo().assertIsDisplayed()
            val browse = composeRule.onNode(hasText("CONTINUE FOR 5 MINUTES") and hasClickAction()).fetchSemanticsNode().boundsInRoot.height
            assertTrue("Exercise action must be at least as tall as browse", exercise >= browse)
            // Scroll back to the top before capturing the screen.
            composeRule.onNodeWithText("Leave browser").performScrollTo()
            capture("${theme.name.lowercase()}-gate")
            composeRule.onNodeWithContentDescription("How exercise points work").performScrollTo().performClick()
            composeRule.onNodeWithText(
                "1 point equals:\n\n1 push-up\n2 machine reps\n3 seconds of plank\n\nMix exercises to earn 5 points for +3 minutes. Partial points carry over until 04:00.",
            ).assertIsDisplayed()
            capture("${theme.name.lowercase()}-points-help")
            composeRule.onNodeWithText("Got it").performClick()
        }
    }

    private fun capture(name: String) {
        composeRule.waitForIdle()
        android.os.SystemClock.sleep(350L) // Allow the platform dialog/window transition to finish.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "pulse-review").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
