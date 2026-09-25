package com.michalkrsik.internetrestrictor.ui

import android.app.Application
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.ProtectionHealth
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.ui.components.HealthCard
import com.michalkrsik.internetrestrictor.ui.components.healthCopy
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp", application = Application::class)
class HealthCardTest {
    @get:Rule val rule = createComposeRule()

    @Test fun everyHealthStateOffersTheAppropriateRecoveryActionInBothLayouts() {
        val health = mutableStateOf(ProtectionHealth.READY)
        val compact = mutableStateOf(false)
        var clicked = 0
        rule.setContent { RestrictorTheme(ThemePreference.LIGHT) {
            HealthCard(health.value, false, { clicked++ }, compact = compact.value)
        } }
        val actions = mapOf(
            ProtectionHealth.READY to null,
            ProtectionHealth.READY_LIMITED_RECOVERY to "Improve recovery",
            ProtectionHealth.ACCESSIBILITY_REQUIRED to "Open Accessibility",
            ProtectionHealth.SERVICE_DISCONNECTED to "Repair service",
            ProtectionHealth.BROWSERS_NOT_FOUND to "Get a supported app",
            ProtectionHealth.STORAGE_ERROR to "Open app info",
            ProtectionHealth.ADVANCED_PROTECTION_BLOCKED to "Open Android help",
        )
        assertEquals(ProtectionHealth.entries.toSet(), actions.keys)
        for (small in listOf(false, true)) for ((state, action) in actions) {
            rule.runOnIdle { health.value = state; compact.value = small }
            if (action == null) rule.onAllNodes(hasClickAction()).assertCountEquals(0)
            else rule.onNodeWithText(action).performClick()
            val copy = healthCopy(state)
            rule.onNodeWithText(copy.title).assertIsDisplayed()
            if (small) rule.onNodeWithText(copy.body).assertDoesNotExist()
            else rule.onNodeWithText(copy.body).assertIsDisplayed()
        }
        rule.runOnIdle { assertEquals(actions.values.count { it != null } * 2, clicked) }
    }

    @Test fun usageAccessRemovesTheRecoveryPromptWithoutClaimingCompleteRecovery() {
        val access = mutableStateOf(false)
        rule.setContent { RestrictorTheme(ThemePreference.DARK) {
            HealthCard(ProtectionHealth.READY_LIMITED_RECOVERY, access.value, {})
        } }
        rule.onNodeWithText("Protected with limited recovery").assertIsDisplayed()
        rule.onNodeWithText("Improve recovery").assertIsDisplayed()
        rule.runOnIdle { access.value = true }
        rule.onNodeWithText("Protection ready").assertIsDisplayed()
        rule.onNodeWithText("Improve recovery").assertDoesNotExist()
        rule.onNodeWithText("Recovery after a process interruption remains limited in this build.", substring = true).assertIsDisplayed()
        assertTrue(healthCopy(ProtectionHealth.READY_LIMITED_RECOVERY, true).positive)
    }
}
