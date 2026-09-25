package com.michalkrsik.internetrestrictor.ui

import android.app.Application
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.onboarding.OnboardingScreen
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], qualifiers = "w411dp-h891dp", application = Application::class)
class OnboardingScreenTest {
    @get:Rule val rule = createComposeRule()
    @Test fun setupRequiresAccessibilityButKeepsUsageRecoveryOptionalAndDispatchesOnlyCallbacks() {
        val snapshot = mutableStateOf(RestrictionEngine().snapshot(PersistedState(), RuntimeSignals(), 1_790_250_000_000L, ZoneId.of("UTC")))
        val events = mutableListOf<String>()
        rule.setContent { RestrictorTheme(ThemePreference.DARK) {
            OnboardingScreen(snapshot.value, { events += "accessibility" }, { events += "usage" },
                { events += "info" }, { events += "test" }, { events += "complete" })
        } }
        rule.onNodeWithText("SETUP  1 / 5").assertIsDisplayed()
        rule.onNodeWithText("Your setup, connected").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Continue").performScrollTo().performClick()
        rule.onNodeWithText("Earn your time").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Back").performScrollTo().performClick()
        rule.onNodeWithText("SETUP  1 / 5").performScrollTo().assertIsDisplayed()
        repeat(2) { rule.onNodeWithText("Continue").performScrollTo().performClick() }
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithText("Open Accessibility settings").performScrollTo().performClick()
        if (Build.VERSION.SDK_INT >= 33) rule.onNodeWithText("Open app info").performScrollTo().performClick()
        else rule.onNodeWithText("Open app info").assertDoesNotExist()
        rule.runOnIdle { snapshot.value = snapshot.value.copy(accessibilityEnabled = true) }
        rule.onNodeWithText("Accessibility enabled ✓").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Continue").performScrollTo().performClick()
        rule.onNodeWithText("Open Usage Access settings").performScrollTo().performClick()
        rule.onNodeWithText("Continue").performScrollTo().assertIsEnabled()
        rule.runOnIdle { snapshot.value = snapshot.value.copy(usageAccessGranted = true) }
        rule.onNodeWithText("Usage Access granted ✓").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Continue").performScrollTo().performClick()
        rule.onNodeWithText("Repair").performScrollTo().performClick()
        rule.runOnIdle { snapshot.value = snapshot.value.copy(serviceConnected = true, supportedBrowserInstalled = false) }
        rule.onNodeWithText("Test browser gate").performScrollTo().assertIsNotEnabled()
        rule.runOnIdle { snapshot.value = snapshot.value.copy(supportedBrowserInstalled = true) }
        rule.onNodeWithText("Test browser gate").performClick()
        rule.onNodeWithText("Finish setup").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOfNotNull("accessibility", "info".takeIf { Build.VERSION.SDK_INT >= 33 }, "usage", "accessibility", "test", "complete"), events)
        }
    }
}
