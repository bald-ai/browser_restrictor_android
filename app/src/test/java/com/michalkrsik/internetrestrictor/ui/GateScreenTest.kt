package com.michalkrsik.internetrestrictor.ui

import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.michalkrsik.internetrestrictor.domain.BudgetPeriod
import com.michalkrsik.internetrestrictor.domain.DAILY_BASE_BUDGET_MINUTES
import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.PersistedState
import com.michalkrsik.internetrestrictor.domain.ProtectedTarget
import com.michalkrsik.internetrestrictor.domain.RestrictionEngine
import com.michalkrsik.internetrestrictor.domain.RuntimeSignals
import com.michalkrsik.internetrestrictor.domain.UsagePeriodState
import com.michalkrsik.internetrestrictor.ui.gate.GateActions
import com.michalkrsik.internetrestrictor.ui.gate.GateScreen
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import java.time.ZoneId
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35], qualifiers = "w411dp-h891dp", application = android.app.Application::class)
class GateScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val engine = RestrictionEngine()
    private val now = 1_783_683_200_000L
    private val runtime = RuntimeSignals(
        foregroundClass = ForegroundClass.BROWSER,
        activeTarget = ProtectedTarget.BROWSER_POOL,
        gateFocused = true,
        serviceConnected = true,
        accessibilityEnabled = true,
        storageReady = true,
        gateSessionId = "gate",
    )
    private val actions = GateActions({ _ -> }, {}, {}, {}, {})

    @Test
    fun taskEntryInvokesTheSharedTaskSelectionAction() {
        val state = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val snapshot = engine.snapshot(state, runtime, now)
        var selected = 0
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) {
                GateScreen(snapshot, actions.copy(onExerciseChallenge = { selected++ }))
            }
        }
        composeRule.onNodeWithText("PROGRESS TO NEXT CREDIT").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CHOOSE A TASK").performScrollTo().performClick()
        composeRule.runOnIdle { org.junit.Assert.assertEquals(1, selected) }
        composeRule.onAllNodesWithText("START PUSH-UPS").assertCountEquals(0)
        composeRule.onAllNodesWithText("START SPRINT").assertCountEquals(0)
    }

    @Test
    fun pausedGateShowsOnlyLockedSessionChoices() {
        val normalized = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val state = when (val result = engine.earnPushupBudget(normalized, now, ZoneId.of("UTC"))) {
            is com.michalkrsik.internetrestrictor.domain.CommandResult.Applied -> result.state.copy(
                dailyCounter = result.state.dailyCounter.copy(grantedBudgetMinutes = 3),
            )
            else -> error("Expected budget to be earned")
        }
        val snapshot = engine.snapshot(state, runtime, now)
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) { GateScreen(snapshot, actions) }
        }

        composeRule.onNodeWithText("3 MIN").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithContentDescription("3 minute session").performScrollTo().assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithContentDescription("5 minute session, not enough time left").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("10 minute session, not enough time left").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Unlock · 3 minutes").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(
            "A 12-second pause before each session",
        ).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CHOOSE A TASK").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Earn 3 minutes with your next credit").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("PROGRESS TO NEXT CREDIT").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Leave browser").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("Settings", substring = true).assertCountEquals(0)
    }

    @Test
    fun selectionFallsBackToTheDefaultWhenTheChosenLengthDisappears() {
        val normalized = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val earned = when (val result = engine.earnPushupBudget(normalized, now, ZoneId.of("UTC"))) {
            is com.michalkrsik.internetrestrictor.domain.CommandResult.Applied -> result.state
            else -> error("Expected budget to be earned")
        }
        fun withBudget(minutes: Long) = engine.snapshot(
            earned.copy(dailyCounter = earned.dailyCounter.copy(grantedBudgetMinutes = minutes)), runtime, now,
        )
        val snapshot = androidx.compose.runtime.mutableStateOf(withBudget(2))
        composeRule.setContent {
            RestrictorTheme(snapshot.value.settings.theme) { GateScreen(snapshot.value, actions) }
        }
        composeRule.onNodeWithContentDescription("2 minute session").performScrollTo().performClick()

        // Earning time on the same gate removes the "2" option; the default, not the longest, takes over.
        composeRule.runOnIdle { snapshot.value = withBudget(12) }
        composeRule.onNodeWithText("Unlock · 5 minutes").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun budgetBelowShortestPresetReplacesItWithTheRemainingMinutes() {
        val normalized = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val state = when (val result = engine.earnPushupBudget(normalized, now, ZoneId.of("UTC"))) {
            is com.michalkrsik.internetrestrictor.domain.CommandResult.Applied -> result.state.copy(
                dailyCounter = result.state.dailyCounter.copy(grantedBudgetMinutes = 2),
            )
            else -> error("Expected budget to be earned")
        }
        val snapshot = engine.snapshot(state, runtime, now)
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) { GateScreen(snapshot, actions) }
        }

        composeRule.onNodeWithContentDescription("2 minute session").performScrollTo().assertIsEnabled()
        composeRule.onAllNodesWithContentDescription("3 minute session", substring = true).assertCountEquals(0)
        composeRule.onNodeWithContentDescription("5 minute session, not enough time left").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Unlock · 2 minutes").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun outOfTimeGateHidesSkipAndSessionControls() {
        val normalized = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val base = normalized.copy(
            dailyCounter = normalized.dailyCounter.copy(pushupRepetitionRemainder = 4),
        )
        val exhausted = base.copy(
            dailyUsage = UsagePeriodState(
                BudgetPeriod.DAILY,
                base.dailyUsage.key,
                DAILY_BASE_BUDGET_MINUTES * 60_000,
            ),
        )
        val snapshot = engine.snapshot(
            exhausted,
            runtime.copy(outOfTimeLatchToken = "latch", latchedBlockers = setOf(BudgetPeriod.DAILY)),
            now,
        )
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) { GateScreen(snapshot, actions) }
        }

        composeRule.onNodeWithText("BROWSER TIME IS USED UP.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CHOOSE A TASK").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("PROGRESS TO NEXT CREDIT").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Try again").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("minute session", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Unlock ·", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("emergency skip", substring = true).assertCountEquals(0)
    }

    @Test
    fun youtubeGateOffersTaskSelectionAndLeaveActions() {
        val normalized = engine.normalize(PersistedState(), now, ZoneId.of("UTC")).copy(
            dailyCounter = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
                .dailyCounter.copy(
                    earnedBudgetRounds = 3,
                    spendableBudgetRounds = 3,
                    pushupRepetitionRemainder = 4,
                ),
        )
        val snapshot = engine.snapshot(
            normalized,
            runtime.copy(
                foregroundClass = ForegroundClass.OTHER,
                activeTarget = ProtectedTarget.YOUTUBE,
            ),
            now,
        )
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) { GateScreen(snapshot, actions) }
        }

        composeRule.onNodeWithText("EXERCISE BEFORE YOUTUBE.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Unlock YouTube").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("3/4 credits today · until 04:00").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Leave YouTube").performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText("minute session", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Unlock ·", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("emergency skip", substring = true).assertCountEquals(0)
    }

    @Test
    fun waitingGateShowsMinimalCountdownAndBudgetOnly() {
        val normalized = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val base = when (val result = engine.earnPushupBudget(normalized, now, ZoneId.of("UTC"))) {
            is com.michalkrsik.internetrestrictor.domain.CommandResult.Applied -> result.state.copy(
                dailyCounter = result.state.dailyCounter.copy(grantedBudgetMinutes = 3),
            )
            else -> error("Expected budget to be earned")
        }
        val waiting = when (
            val result = engine.startWait(base, runtime, base.stateRevision, 3, now, ZoneId.of("UTC"))
        ) {
            is com.michalkrsik.internetrestrictor.domain.CommandResult.Applied -> result.state.copy(
                dailyCounter = result.state.dailyCounter.copy(grantedBudgetMinutes = 3),
            )
            else -> error("Expected wait to start")
        }
        val snapshot = engine.snapshot(waiting, runtime, now)
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) { GateScreen(snapshot, actions) }
        }

        composeRule.onNodeWithText("UNLOCKING BROWSER").assertIsDisplayed()
        composeRule.onNodeWithText("3 min session").assertIsDisplayed()
        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText("Daily budget").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("push-up", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("emergency", substring = true).assertCountEquals(0)
    }
}
