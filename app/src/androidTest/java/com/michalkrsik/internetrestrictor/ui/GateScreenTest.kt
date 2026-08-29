package com.michalkrsik.internetrestrictor.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
    fun pausedGateShowsOnlyLockedSessionChoices() {
        val normalized = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val state = when (val result = engine.earnPushupBudget(normalized, now, ZoneId.of("UTC"))) {
            is com.michalkrsik.internetrestrictor.domain.CommandResult.Applied -> result.state
            else -> error("Expected budget to be earned")
        }
        val snapshot = engine.snapshot(state, runtime, now)
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) { GateScreen(snapshot, actions) }
        }

        composeRule.onNodeWithText("Pause before browsing.").assertIsDisplayed()
        composeRule.onNodeWithText("3 min").assertIsDisplayed()
        composeRule.onNodeWithText("5 min").assertIsDisplayed()
        composeRule.onNodeWithText("10 min").assertIsDisplayed()
        composeRule.onNodeWithText("Begin 3 minute session").assertIsDisplayed()
        composeRule.onNodeWithText(
            "Every normal browser session starts with the same 12 sec focused wait.",
        ).assertIsDisplayed()
        composeRule.onNodeWithText("Start exercise").assertIsDisplayed()
        composeRule.onNodeWithText("Each set earns +3 minutes").assertIsDisplayed()
        composeRule.onNodeWithText("5 push-ups · 18s plank · 10 machine reps").assertIsDisplayed()
        composeRule.onNodeWithText("0/5 push-ups carried").assertIsDisplayed()
        composeRule.onNodeWithText("0/18s plank carried").assertIsDisplayed()
        composeRule.onNodeWithText("0/10 machine reps carried").assertIsDisplayed()
        composeRule.onNodeWithText("←  Leave browser").assertIsDisplayed()
        composeRule.onAllNodesWithText("Settings", substring = true).assertCountEquals(0)
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

        composeRule.onNodeWithText("Browser time is used up.").assertIsDisplayed()
        composeRule.onNodeWithText("Start exercise").assertIsDisplayed()
        composeRule.onNodeWithText("4/5 push-ups carried").assertIsDisplayed()
        composeRule.onNodeWithText("0/18s plank carried").assertIsDisplayed()
        composeRule.onNodeWithText("0/10 machine reps carried").assertIsDisplayed()
        composeRule.onNodeWithText("Try again").assertIsDisplayed()
        composeRule.onAllNodesWithText("minute session", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("emergency skip", substring = true).assertCountEquals(0)
    }

    @Test
    fun youtubeGateOffersOnlyTheFirstPushupRoundAndLeaveActions() {
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

        composeRule.onNodeWithText("Exercise before YouTube.").assertIsDisplayed()
        composeRule.onNodeWithText("Start exercise").assertIsDisplayed()
        composeRule.onNodeWithText("3/4 sets today · unlocks until 04:00").assertIsDisplayed()
        composeRule.onNodeWithText("←  Leave YouTube").assertIsDisplayed()
        composeRule.onAllNodesWithText("minute session", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("emergency skip", substring = true).assertCountEquals(0)
    }

    @Test
    fun waitingGateShowsMinimalCountdownAndBudgetOnly() {
        val normalized = engine.normalize(PersistedState(), now, ZoneId.of("UTC"))
        val base = when (val result = engine.earnPushupBudget(normalized, now, ZoneId.of("UTC"))) {
            is com.michalkrsik.internetrestrictor.domain.CommandResult.Applied -> result.state
            else -> error("Expected budget to be earned")
        }
        val waiting = when (
            val result = engine.startWait(base, runtime, base.stateRevision, 5, now, ZoneId.of("UTC"))
        ) {
            is com.michalkrsik.internetrestrictor.domain.CommandResult.Applied -> result.state
            else -> error("Expected wait to start")
        }
        val snapshot = engine.snapshot(waiting, runtime, now)
        composeRule.setContent {
            RestrictorTheme(snapshot.settings.theme) { GateScreen(snapshot, actions) }
        }

        composeRule.onNodeWithText("Unlocking browser").assertIsDisplayed()
        composeRule.onNodeWithText("5 min session").assertIsDisplayed()
        composeRule.onNodeWithText("12").assertIsDisplayed()
        composeRule.onNodeWithText("Daily budget").assertIsDisplayed()
        composeRule.onNodeWithText("Cancel", substring = true).assertIsDisplayed()
        composeRule.onAllNodesWithText("push-up", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("emergency", substring = true).assertCountEquals(0)
    }
}
