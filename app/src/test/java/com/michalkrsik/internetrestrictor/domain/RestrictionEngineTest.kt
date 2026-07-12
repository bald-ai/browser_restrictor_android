package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictionEngineTest {
    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val ids = object : IdGenerator {
        private var next = 0
        override fun nextId(): String = "id-${++next}"
    }
    private val engine = RestrictionEngine(ids)
    private val ready = RuntimeSignals(
        foregroundClass = ForegroundClass.CHROME,
        chromeActive = true,
        gateFocused = true,
        screenInteractive = true,
        keyguardUnlocked = true,
        serviceConnected = true,
        accessibilityEnabled = true,
        storageReady = true,
        gateSessionId = "gate-1",
    )

    private fun initial(settings: RestrictorSettings = RestrictorSettings()): PersistedState =
        engine.normalize(PersistedState(settings = settings), now, zone)

    @Test
    fun lockedDefaultsMatchRunnableDesktopRules() {
        val settings = RestrictorSettings()
        assertTrue(settings.enforcementEnabled)
        assertTrue(settings.delayEnabled)
        assertEquals(60, settings.dailyBudgetMinutes)
        assertEquals(600, settings.weeklyBudgetMinutes)
        assertEquals(2_400, settings.monthlyBudgetMinutes)
        assertEquals(3, settings.emergencySkipDailyLimit)
        assertEquals(ThemePreference.DARK, settings.theme)
    }

    @Test
    fun statePriorityStartsPaused() {
        assertEquals(GateMode.PAUSED, engine.underlyingMode(initial(), ready, now))
    }

    @Test
    fun exhaustedBudgetOutranksValidUnlock() {
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 60 * MINUTE_MS),
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, now, now + HOUR_MS),
        )
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(state, ready, now))
    }

    @Test
    fun noDelayStillRespectsBudgetButOtherwiseGrantsAccess() {
        val settings = RestrictorSettings(delayEnabled = false)
        val state = initial(settings)
        assertEquals(GateMode.ACCESS_GRANTED_NO_DELAY, engine.underlyingMode(state, ready, now))
        val exhausted = state.copy(dailyUsage = state.dailyUsage.copy(usedMs = 60 * MINUTE_MS))
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(exhausted, ready, now))
    }

    @Test
    fun enabledZeroBudgetBlocksImmediatelyAndShowsFullBar() {
        val state = initial(RestrictorSettings(dailyBudgetMinutes = 0))
        val daily = engine.budgetSnapshots(state).first { it.period == BudgetPeriod.DAILY }
        assertTrue(daily.exhausted)
        assertEquals(100, daily.percentage)
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(state, ready, now))
    }

    @Test
    fun allBudgetsDisabledStillAllowsDelayAndUsageAccumulation() {
        val settings = RestrictorSettings(
            delayEnabled = false,
            dailyBudgetEnabled = false,
            weeklyBudgetEnabled = false,
            monthlyBudgetEnabled = false,
        )
        val state = initial(settings)
        val used = engine.consumeUsage(state, ready, now, now + 10_000, zone)
        assertEquals(10_000, used.dailyUsage.usedMs)
        assertEquals(10_000, used.weeklyUsage.usedMs)
        assertEquals(10_000, used.monthlyUsage.usedMs)
    }

    @Test
    fun startWaitUsesSnapshotRevisionAndCreatesFixedCost() {
        val state = initial()
        val result = engine.startWait(state, ready, state.stateRevision, 30, now, zone)
        assertTrue(result is CommandResult.Applied)
        val pending = (result as CommandResult.Applied).state.pendingWait
        assertNotNull(pending)
        assertEquals(15_000, pending!!.requiredFocusMs)
        assertEquals(30 * MINUTE_MS, pending.selectedDurationMs)
    }

    @Test
    fun staleStartIsRejected() {
        val state = initial()
        val result = engine.startWait(state, ready, state.stateRevision - 1, 10, now, zone)
        assertTrue(result is CommandResult.Rejected)
        assertNull((result as CommandResult.Rejected).state.pendingWait)
    }

    @Test
    fun unfocusedWaitDoesNotAdvance() {
        val started = applied(engine.startWait(initial(), ready, initial().stateRevision, 10, now, zone))
        val result = engine.advanceWait(
            started,
            ready.copy(gateFocused = false),
            1_000,
            now + 1_000,
            zone,
            "gate-1",
        )
        assertTrue(result is CommandResult.Rejected)
        assertEquals(0, (result as CommandResult.Rejected).state.pendingWait!!.accumulatedFocusMs)
    }

    @Test
    fun focusedCompletionIsAtomicAndIncrementsCostOnce() {
        val start = initial()
        val waiting = applied(engine.startWait(start, ready, start.stateRevision, 10, now, zone))
        val completed = applied(
            engine.advanceWait(waiting, ready, 5_000, now + 5_000, zone, "gate-1"),
        )
        assertNull(completed.pendingWait)
        assertEquals(UnlockSource.NORMAL, completed.activeUnlock!!.source)
        assertEquals(1, completed.dailyCounter.completedNormalUnlocks)

        val duplicate = engine.completeNormalWait(
            completed,
            ready,
            now + 5_100,
            zone,
            waiting.pendingWait!!.id,
            "gate-1",
        )
        assertTrue(duplicate is CommandResult.Rejected)
        assertEquals(1, (duplicate as CommandResult.Rejected).state.dailyCounter.completedNormalUnlocks)
    }

    @Test
    fun emergencySkipClearsWaitWithoutIncreasingNormalCost() {
        val start = initial()
        val waiting = applied(engine.startWait(start, ready, start.stateRevision, 30, now, zone))
        val skipped = applied(
            engine.emergencySkip(
                waiting,
                ready,
                waiting.stateRevision,
                waiting.pendingWait!!.id,
                now + 1_000,
                zone,
            ),
        )
        assertNull(skipped.pendingWait)
        assertEquals(UnlockSource.EMERGENCY, skipped.activeUnlock!!.source)
        assertEquals(1, skipped.dailyCounter.emergencySkipsUsed)
        assertEquals(0, skipped.dailyCounter.completedNormalUnlocks)
    }

    @Test
    fun emergencySkipDurationIsCappedByTightestBudget() {
        val state = initial().copy(dailyUsage = initial().dailyUsage.copy(usedMs = 55 * MINUTE_MS))
        val snapshot = engine.snapshot(state, ready, now)
        assertEquals(5 * MINUTE_MS, snapshot.skipSnapshot.grantDurationMs)
        assertTrue(snapshot.skipSnapshot.eligible)
    }

    @Test
    fun emergencySkipCannotBypassExhaustionOrConsumeCount() {
        val state = initial().copy(dailyUsage = initial().dailyUsage.copy(usedMs = 60 * MINUTE_MS))
        val result = engine.emergencySkip(state, ready, state.stateRevision, null, now, zone)
        assertTrue(result is CommandResult.Rejected)
        assertEquals(0, (result as CommandResult.Rejected).state.dailyCounter.emergencySkipsUsed)
    }

    @Test
    fun normalCompletionWinsSkipRace() {
        val state = initial()
        val waiting = applied(engine.startWait(state, ready, state.stateRevision, 10, now, zone))
        val atEnd = waiting.copy(
            pendingWait = waiting.pendingWait!!.copy(accumulatedFocusMs = waiting.pendingWait.requiredFocusMs),
        )
        val result = engine.emergencySkip(
            atEnd,
            ready,
            atEnd.stateRevision,
            atEnd.pendingWait!!.id,
            now + 5_000,
            zone,
        )
        assertTrue(result is CommandResult.NormalCompletionWon)
        val completed = (result as CommandResult.NormalCompletionWon).state
        assertEquals(UnlockSource.NORMAL, completed.activeUnlock!!.source)
        assertEquals(1, completed.dailyCounter.completedNormalUnlocks)
        assertEquals(0, completed.dailyCounter.emergencySkipsUsed)
    }

    @Test
    fun usageStopsExactlyAtTightestBudget() {
        val state = initial(RestrictorSettings(delayEnabled = false)).copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 60 * MINUTE_MS - 500),
        )
        val used = engine.consumeUsage(state, ready, now, now + 2_000, zone)
        assertEquals(60 * MINUTE_MS, used.dailyUsage.usedMs)
        assertEquals(500, used.weeklyUsage.usedMs)
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(used, ready, now + 2_000))
    }

    @Test
    fun activeUsageIsSplitAtDailyBoundary() {
        val start = ZonedDateTime.of(2026, 7, 10, 23, 59, 59, 500_000_000, zone).toInstant().toEpochMilli()
        val state = engine.normalize(
            PersistedState(settings = RestrictorSettings(delayEnabled = false)),
            start,
            zone,
        )
        val used = engine.consumeUsage(state, ready, start, start + 1_000, zone)
        assertEquals("2026-07-11", used.dailyUsage.key)
        assertEquals(500, used.dailyUsage.usedMs)
        assertEquals(1_000, used.weeklyUsage.usedMs)
        assertEquals(1_000, used.monthlyUsage.usedMs)
    }

    @Test
    fun pendingWaitKeepsPriceAcrossMidnightButDailyCountResets() {
        val beforeMidnight = ZonedDateTime.of(2026, 7, 10, 23, 59, 58, 0, zone).toInstant().toEpochMilli()
        val base = engine.normalize(PersistedState(), beforeMidnight, zone).copy(
            dailyCounter = DailyCounter("2026-07-10", completedNormalUnlocks = 3),
        )
        val waiting = applied(
            engine.startWait(base, ready, base.stateRevision, 10, beforeMidnight, zone),
        )
        assertEquals(20_000, waiting.pendingWait!!.requiredFocusMs)
        val after = engine.normalize(waiting, beforeMidnight + 3_000, zone)
        assertEquals(20_000, after.pendingWait!!.requiredFocusMs)
        assertEquals(0, after.dailyCounter.completedNormalUnlocks)
    }

    @Test
    fun budgetResetDoesNotDeleteStillValidUnlock() {
        val before = ZonedDateTime.of(2026, 7, 10, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        val base = engine.normalize(PersistedState(), before, zone)
        val state = base.copy(
            dailyUsage = base.dailyUsage.copy(usedMs = 60 * MINUTE_MS),
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, before, before + 10 * MINUTE_MS),
        )
        val after = engine.normalize(state, before + 2 * MINUTE_MS, zone)
        assertEquals(0, after.dailyUsage.usedMs)
        assertNotNull(after.activeUnlock)
        assertEquals(GateMode.ACCESS_GRANTED_NORMAL, engine.underlyingMode(after, ready, before + 2 * MINUTE_MS))
    }

    @Test
    fun enforcementOffKeepsPendingAndUsage() {
        val state = initial()
        val waiting = applied(engine.startWait(state, ready, state.stateRevision, 10, now, zone))
        val off = engine.updateImmediateSettings(
            waiting,
            { it.copy(enforcementEnabled = false) },
            now,
            zone,
        )
        assertEquals(GateMode.ENFORCEMENT_DISABLED, engine.underlyingMode(off, ready, now))
        assertNotNull(off.pendingWait)
        assertEquals(waiting.dailyUsage, off.dailyUsage)
    }

    @Test
    fun resetClearsAllRuntimeStateButKeepsRulesAndTheme() {
        val settings = RestrictorSettings(theme = ThemePreference.LIGHT, dailyBudgetMinutes = 17)
        val base = initial(settings)
        val dirty = base.copy(
            dailyUsage = base.dailyUsage.copy(usedMs = 3_000),
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, now, now + MINUTE_MS),
            dailyCounter = base.dailyCounter.copy(completedNormalUnlocks = 2, emergencySkipsUsed = 2),
        )
        val reset = engine.resetUsage(dirty, now, zone)
        assertEquals(0, reset.dailyUsage.usedMs)
        assertNull(reset.activeUnlock)
        assertNull(reset.pendingWait)
        assertEquals(0, reset.dailyCounter.completedNormalUnlocks)
        assertEquals(0, reset.dailyCounter.emergencySkipsUsed)
        assertEquals(17, reset.settings.dailyBudgetMinutes)
        assertEquals(ThemePreference.LIGHT, reset.settings.theme)
    }

    @Test
    fun invalidSettingsCannotSave() {
        val settings = initial().settings.copy(dailyBudgetMinutes = -1)
        assertFalse(engine.validateSettings(settings))
    }

    private fun applied(result: CommandResult): PersistedState = when (result) {
        is CommandResult.Applied -> result.state
        is CommandResult.NormalCompletionWon -> result.state
        is CommandResult.Rejected -> error("Expected applied, got ${result.reason}")
    }
}

