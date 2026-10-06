package com.michalkrsik.internetrestrictor.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.gate.BadgeContent
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp", application = Application::class)
class BadgeContentTest {
    @get:Rule val rule = createComposeRule()
    @Test fun badgeShowsRemainingSessionAndBudgetAndDragUsesTheCallback() {
        val now = 1_790_250_000_000L
        val initial = RestrictionEngine().snapshot(PersistedState(), RuntimeSignals(), now, ZoneId.of("UTC"))
            .withBudgetRemaining(10 * MINUTE_MS)
        val snapshot = mutableStateOf(initial.copy(activeUnlock = UnlockSession("session", UnlockSource.NORMAL, now, now + 5 * MINUTE_MS)))
        val drags = mutableListOf<Float>()
        rule.setContent { RestrictorTheme(ThemePreference.DARK) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) { BadgeContent(snapshot.value, drags::add) }
        } }
        rule.onNodeWithText("5").assertIsDisplayed()
        val collapsed = rule.onNodeWithContentDescription("Swipe left to expand", substring = true)
        collapsed.performClick()
        // Move beyond the compact badge's bounds: this remains an in-memory Compose gesture.
        collapsed.performTouchInput { swipe(center, center + Offset(-180f, 0f), 400) }
        rule.onNodeWithText("Available").assertIsDisplayed()
        rule.onNodeWithContentDescription("Swipe right to collapse", substring = true).performTouchInput {
            swipe(center, center + Offset(0f, 60f), 400)
        }
        rule.runOnIdle { assertTrue(drags.any { it > 0 }) }
        rule.runOnIdle { snapshot.value = snapshot.value.copy(activeUnlock = null, canonicalMode = GateMode.ACCESS_GRANTED_CURRENT_VISIT, budgetSnapshots = emptyList()) }
        rule.onNodeWithText("This visit").assertIsDisplayed()
        rule.onNodeWithText("No limit").assertIsDisplayed()
        rule.onNodeWithContentDescription("Swipe right to collapse", substring = true).performTouchInput {
            swipe(center, center + Offset(180f, 0f), 400)
        }
        rule.onNodeWithContentDescription("Swipe left to expand", substring = true).assertExists()
        rule.onNodeWithText("Available").assertDoesNotExist()
        rule.runOnIdle { snapshot.value = snapshot.value.copy(canonicalMode = GateMode.ACCESS_GRANTED_NORMAL) }
        rule.onNodeWithText("Browser available").assertIsDisplayed()
        rule.mainClock.advanceTimeBy(1600)
        rule.onNodeWithText("Browser available").assertIsDisplayed()
    }

    @Test fun normalBadgeCountsDownTheBudgetWhenItEndsBeforeTheWindow() {
        val now = 1_790_250_000_000L
        val unlock = UnlockSession("session", UnlockSource.NORMAL, now, now + 5 * MINUTE_MS)
        val base = RestrictionEngine().snapshot(PersistedState(), RuntimeSignals(), now, ZoneId.of("UTC"))
            .copy(activeUnlock = unlock)
        val snapshot = mutableStateOf(base.withBudgetRemaining(2 * MINUTE_MS))
        rule.setContent { RestrictorTheme(ThemePreference.DARK) { BadgeContent(snapshot.value) {} } }
        rule.onNodeWithText("2").assertIsDisplayed()
        rule.onNodeWithContentDescription("Unlock 2m left", substring = true).assertExists()

        // The minute in progress still counts until it is completed.
        rule.runOnIdle { snapshot.value = base.copy(wallNowMs = now + 30_000).withBudgetRemaining(90_000) }
        rule.onNodeWithText("2").assertIsDisplayed()

        // Emergency access is free, so only its window counts down.
        rule.runOnIdle {
            snapshot.value = base.copy(activeUnlock = unlock.copy(source = UnlockSource.EMERGENCY))
                .withBudgetRemaining(0)
        }
        rule.onNodeWithText("5").assertIsDisplayed()
    }

    private fun StateSnapshot.withBudgetRemaining(remainingMs: Long) =
        copy(budgetSnapshots = budgetSnapshots.map { it.copy(remainingMs = remainingMs, exhausted = remainingMs == 0L) })
}
