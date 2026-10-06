package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class WholeMinuteBudgetTest {
    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val engine = RestrictionEngine()
    private val ready = RuntimeSignals(
        foregroundClass = ForegroundClass.BROWSER,
        activeTarget = ProtectedTarget.BROWSER_POOL,
        gateFocused = true,
        screenInteractive = true,
        keyguardUnlocked = true,
        serviceConnected = true,
        accessibilityEnabled = true,
        storageReady = true,
        gateSessionId = "gate-1",
    )

    private fun applied(result: CommandResult) = (result as CommandResult.Applied).state

    private fun withBudget(minutes: Long): PersistedState {
        val earned = applied(engine.earnPushupBudget(engine.normalize(PersistedState(), now, zone), now, zone))
        return earned.copy(dailyCounter = earned.dailyCounter.copy(grantedBudgetMinutes = minutes))
    }

    private fun remainingMs(state: PersistedState) = engine.budgetSnapshots(state).single().remainingMs

    @Test fun unfinishedMinuteIsForgivenWhenTheUnlockEnds() {
        val unlocked = withBudget(5).copy(
            activeUnlock = UnlockSession("normal", UnlockSource.NORMAL, now, now + 3 * MINUTE_MS),
        )
        val browsed = engine.consumeUsage(unlocked, ready, now, now + 2 * MINUTE_MS + 59_000, zone)
        assertEquals(2 * MINUTE_MS + 59_000, browsed.dailyUsage.usedMs)

        val expired = engine.normalize(browsed, now + 3 * MINUTE_MS, zone)
        assertNull(expired.activeUnlock)
        assertEquals(2 * MINUTE_MS, expired.dailyUsage.usedMs)
        assertEquals(3 * MINUTE_MS, remainingMs(expired))
    }

    @Test fun fiftyNineSecondsCostNothingAndSixtyCostOneMinute() {
        fun spent(browsedMs: Long): Long {
            val unlocked = withBudget(5).copy(
                activeUnlock = UnlockSession("normal", UnlockSource.NORMAL, now, now + 3 * MINUTE_MS),
            )
            val browsed = engine.consumeUsage(unlocked, ready, now, now + browsedMs, zone)
            return engine.cancelActiveUnlock(browsed, now + browsedMs, zone)
                .let { engine.normalize(it, now + browsedMs, zone) }.dailyUsage.usedMs
        }
        assertEquals(0L, spent(59_000))
        assertEquals(MINUTE_MS, spent(60_000))
    }

    @Test fun presetsLongerThanTheBudgetAreUnavailable() {
        val state = withBudget(5)
        assertEquals(
            listOf(SessionOption(3, true), SessionOption(5, true), SessionOption(10, false)),
            SessionOptions.options(state.settings.sessionMinutes, engine.budgetSnapshots(state)),
        )
        assertTrue(engine.startWait(state, ready, state.stateRevision, 10, now, zone) is CommandResult.Rejected)
        assertTrue(engine.startWait(state, ready, state.stateRevision, 5, now, zone) is CommandResult.Applied)
    }

    @Test fun budgetBelowTheShortestPresetOffersTheRemainingMinutes() {
        val state = withBudget(2)
        assertEquals(
            listOf(SessionOption(2, true), SessionOption(5, false), SessionOption(10, false)),
            SessionOptions.options(state.settings.sessionMinutes, engine.budgetSnapshots(state)),
        )
        assertTrue(engine.startWait(state, ready, state.stateRevision, 3, now, zone) is CommandResult.Rejected)
        val waiting = applied(engine.startWait(state, ready, state.stateRevision, 2, now, zone))
        assertEquals(2 * MINUTE_MS, waiting.pendingWait!!.selectedDurationMs)
    }

    @Test fun completedWindowIsCappedAtTheBudgetLeftWhenTheWaitEnds() {
        val state = withBudget(5)
        val waiting = applied(engine.startWait(state, ready, state.stateRevision, 5, now, zone))
        // A correction lowered the budget while the wait was running.
        val lowered = waiting.copy(dailyCounter = waiting.dailyCounter.copy(grantedBudgetMinutes = 2))
        val unlocked = applied(engine.advanceWait(lowered, ready, 12_000, now + 12_000, zone, "gate-1"))
        val unlock = unlocked.activeUnlock!!
        assertEquals(2 * MINUTE_MS, unlock.expiresAtWallMs - unlock.grantedAtWallMs)
    }

    @Test fun badgeCountdownUsesWhicheverEndsFirst() {
        val unlock = UnlockSession("normal", UnlockSource.NORMAL, now, now + 5 * MINUTE_MS)
        val snapshot = engine.snapshot(withBudget(2).copy(activeUnlock = unlock), ready, now, zoneId = zone)
        assertEquals(2L, snapshot.unlockCountdown()!!.displayMinutes)

        val roomy = engine.snapshot(withBudget(30).copy(activeUnlock = unlock), ready, now + MINUTE_MS + 1, zoneId = zone)
        assertEquals(4L, roomy.unlockCountdown()!!.displayMinutes)

        val emergency = engine.snapshot(
            withBudget(2).copy(activeUnlock = unlock.copy(source = UnlockSource.EMERGENCY)), ready, now, zoneId = zone,
        )
        assertEquals(5L, emergency.unlockCountdown()!!.displayMinutes)
    }
}
