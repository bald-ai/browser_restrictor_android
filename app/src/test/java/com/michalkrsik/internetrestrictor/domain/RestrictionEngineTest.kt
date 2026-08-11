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
        foregroundClass = ForegroundClass.BROWSER,
        browserActive = true,
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
    fun defaultsUseFixedDailyBudgetAndWaitRules() {
        val settings = RestrictorSettings()
        assertTrue(settings.enforcementEnabled)
        assertTrue(settings.delayEnabled)
        assertEquals(3, settings.emergencySkipDailyLimit)
        assertEquals(ThemePreference.DARK, settings.theme)
        assertEquals(30, engine.budgetSnapshots(initial()).single().limitMinutes)
        assertEquals(12_000, WaitCalculator.requiredFocusMs(10))
    }

    @Test
    fun statePriorityStartsPaused() {
        assertEquals(GateMode.PAUSED, engine.underlyingMode(initial(), ready, now))
    }

    @Test
    fun exhaustedBudgetOutranksValidNormalUnlock() {
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 30 * MINUTE_MS),
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, now, now + HOUR_MS),
        )
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(state, ready, now))
    }

    @Test
    fun validEmergencyUnlockOutranksExhaustedBudget() {
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 30 * MINUTE_MS),
            activeUnlock = UnlockSession("unlock", UnlockSource.EMERGENCY, now, now + HOUR_MS),
        )
        assertEquals(GateMode.ACCESS_GRANTED_EMERGENCY, engine.underlyingMode(state, ready, now))
    }

    @Test
    fun noDelayStillRespectsBudgetButOtherwiseGrantsAccess() {
        val settings = RestrictorSettings(delayEnabled = false)
        val state = initial(settings)
        assertEquals(GateMode.ACCESS_GRANTED_NO_DELAY, engine.underlyingMode(state, ready, now))
        val exhausted = state.copy(dailyUsage = state.dailyUsage.copy(usedMs = 30 * MINUTE_MS))
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(exhausted, ready, now))
    }

    @Test
    fun baseBudgetExhaustionBlocksAndShowsFullBar() {
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 30 * MINUTE_MS),
        )
        val daily = engine.budgetSnapshots(state).first { it.period == BudgetPeriod.DAILY }
        assertTrue(daily.exhausted)
        assertEquals(100, daily.percentage)
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(state, ready, now))
    }

    @Test
    fun noDelayStillAccumulatesDailyUsage() {
        val settings = RestrictorSettings(delayEnabled = false)
        val state = initial(settings)
        val used = engine.consumeUsage(state, ready, now, now + 10_000, zone)
        assertEquals(10_000, used.dailyUsage.usedMs)
    }

    @Test
    fun startWaitUsesSnapshotRevisionAndCreatesFixedCost() {
        val state = initial()
        val result = engine.startWait(state, ready, state.stateRevision, 30, now, zone)
        assertTrue(result is CommandResult.Applied)
        val pending = (result as CommandResult.Applied).state.pendingWait
        assertNotNull(pending)
        assertEquals(12_000, pending!!.requiredFocusMs)
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
    fun focusedCompletionIsAtomic() {
        val start = initial()
        val waiting = applied(engine.startWait(start, ready, start.stateRevision, 10, now, zone))
        val completed = applied(
            engine.advanceWait(waiting, ready, 12_000, now + 12_000, zone, "gate-1"),
        )
        assertNull(completed.pendingWait)
        assertEquals(UnlockSource.NORMAL, completed.activeUnlock!!.source)
        assertEquals(0, completed.dailyCounter.earnedBudgetRounds)

        val duplicate = engine.completeNormalWait(
            completed,
            ready,
            now + 12_100,
            zone,
            waiting.pendingWait!!.id,
            "gate-1",
        )
        assertTrue(duplicate is CommandResult.Rejected)
        assertEquals(0, (duplicate as CommandResult.Rejected).state.dailyCounter.earnedBudgetRounds)
    }

    @Test
    fun emergencySkipClearsWaitWithoutChangingEarnedBudget() {
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
        assertEquals(0, skipped.dailyCounter.earnedBudgetRounds)
    }

    @Test
    fun emergencySkipAlwaysOffersFullThirtyMinutes() {
        val state = initial().copy(dailyUsage = initial().dailyUsage.copy(usedMs = 25 * MINUTE_MS))
        val snapshot = engine.snapshot(state, ready, now)
        assertEquals(30 * MINUTE_MS, snapshot.skipSnapshot.grantDurationMs)
        assertTrue(snapshot.skipSnapshot.eligible)
    }

    @Test
    fun emergencySkipBypassesExhaustionAndConsumesCount() {
        val state = initial().copy(dailyUsage = initial().dailyUsage.copy(usedMs = 30 * MINUTE_MS))
        val skipped = applied(
            engine.emergencySkip(state, ready, state.stateRevision, null, now, zone),
        )
        assertEquals(UnlockSource.EMERGENCY, skipped.activeUnlock!!.source)
        assertEquals(now + 30 * MINUTE_MS, skipped.activeUnlock.expiresAtWallMs)
        assertEquals(1, skipped.dailyCounter.emergencySkipsUsed)
        assertEquals(GateMode.ACCESS_GRANTED_EMERGENCY, engine.underlyingMode(skipped, ready, now))
    }

    @Test
    fun pushupRoundAddsBudgetButDoesNotUnlockBrowser() {
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 30 * MINUTE_MS),
        )
        val earned = applied(engine.earnPushupBudget(state, now, zone))
        assertNull(earned.activeUnlock)
        assertEquals(1, earned.dailyCounter.earnedBudgetRounds)
        assertEquals(0, earned.dailyCounter.emergencySkipsUsed)
        val budget = engine.budgetSnapshots(earned).single()
        assertEquals(60, budget.limitMinutes)
        assertEquals(30 * MINUTE_MS, budget.remainingMs)
        assertEquals(GateMode.PAUSED, engine.underlyingMode(earned, ready, now))
    }

    @Test
    fun pushupRoundCanIncreaseBudgetBeforeExhaustion() {
        val state = initial()
        val earned = applied(engine.earnPushupBudget(state, now, zone))
        assertNull(earned.activeUnlock)
        assertEquals(60, engine.budgetSnapshots(earned).single().limitMinutes)
    }

    @Test
    fun pushupRoundPreservesOrdinaryWaitInsteadOfOpeningBrowser() {
        val state = initial()
        val waiting = applied(
            engine.startWait(state, ready, state.stateRevision, 30, now, zone),
        )

        val earned = applied(engine.earnPushupBudget(waiting, now + 1_000, zone))

        assertEquals(waiting.pendingWait, earned.pendingWait)
        assertNull(earned.activeUnlock)
        assertEquals(GateMode.WAITING, engine.underlyingMode(earned, ready, now + 1_000))
    }

    @Test
    fun exhaustedBudgetCanUseEmergencySkipWhenDelayIsDisabled() {
        val base = initial(RestrictorSettings(delayEnabled = false))
        val state = base.copy(dailyUsage = base.dailyUsage.copy(usedMs = 30 * MINUTE_MS))
        val snapshot = engine.snapshot(state, ready, now)
        assertEquals(GateMode.OUT_OF_TIME, snapshot.canonicalMode)
        assertTrue(snapshot.skipSnapshot.eligible)
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
        assertEquals(0, completed.dailyCounter.earnedBudgetRounds)
        assertEquals(0, completed.dailyCounter.emergencySkipsUsed)
    }

    @Test
    fun usageStopsExactlyAtTightestBudget() {
        val state = initial(RestrictorSettings(delayEnabled = false)).copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 30 * MINUTE_MS - 500),
        )
        val used = engine.consumeUsage(state, ready, now, now + 2_000, zone)
        assertEquals(30 * MINUTE_MS, used.dailyUsage.usedMs)
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(used, ready, now + 2_000))
    }

    @Test
    fun emergencyUsageIsReportedWithoutCreatingFutureBudgetDebt() {
        val base = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 30 * MINUTE_MS),
            activeUnlock = UnlockSession(
                id = "emergency",
                source = UnlockSource.EMERGENCY,
                grantedAtWallMs = now,
                expiresAtWallMs = now + 30 * MINUTE_MS,
            ),
        )
        val used = engine.consumeUsage(base, ready, now, now + 2_000, zone)
        assertEquals(30 * MINUTE_MS, used.dailyUsage.usedMs)
        assertEquals(
            2_000,
            used.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs,
        )
        assertEquals(GateMode.ACCESS_GRANTED_EMERGENCY, engine.underlyingMode(used, ready, now + 2_000))

        val afterEmergency = engine.cancelActiveUnlock(used, now + 2_000, zone)
        val earned = applied(engine.earnPushupBudget(afterEmergency, now + 2_000, zone))
        assertEquals(30 * MINUTE_MS, engine.budgetSnapshots(earned).single().remainingMs)
    }

    @Test
    fun earnedBudgetIsConsumedNormallyAndRemainsInReports() {
        val base = initial(RestrictorSettings(delayEnabled = false)).copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 20 * MINUTE_MS),
        )
        val earned = applied(engine.earnPushupBudget(base, now, zone))
        val used = engine.consumeUsage(earned, ready, now, now + 2_000, zone)
        assertEquals(20 * MINUTE_MS + 2_000, used.dailyUsage.usedMs)
        assertEquals(
            2_000,
            used.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs,
        )
        assertEquals(GateMode.ACCESS_GRANTED_NO_DELAY, engine.underlyingMode(used, ready, now + 2_000))
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
    }

    @Test
    fun pendingWaitStaysFixedAcrossMidnightButEarnedBudgetResets() {
        val beforeMidnight = ZonedDateTime.of(2026, 7, 10, 23, 59, 58, 0, zone).toInstant().toEpochMilli()
        val base = engine.normalize(PersistedState(), beforeMidnight, zone).copy(
            dailyCounter = DailyCounter("2026-07-10", earnedBudgetRounds = 3),
        )
        val waiting = applied(
            engine.startWait(base, ready, base.stateRevision, 10, beforeMidnight, zone),
        )
        assertEquals(12_000, waiting.pendingWait!!.requiredFocusMs)
        val after = engine.normalize(waiting, beforeMidnight + 3_000, zone)
        assertEquals(12_000, after.pendingWait!!.requiredFocusMs)
        assertEquals(0, after.dailyCounter.earnedBudgetRounds)
    }

    @Test
    fun budgetResetDoesNotDeleteStillValidUnlock() {
        val before = ZonedDateTime.of(2026, 7, 10, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        val base = engine.normalize(PersistedState(), before, zone)
        val state = base.copy(
            dailyUsage = base.dailyUsage.copy(usedMs = 30 * MINUTE_MS),
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
        assertFalse(engine.snapshot(off, ready, now).usesBadge)
        assertNotNull(off.pendingWait)
        assertEquals(waiting.dailyUsage, off.dailyUsage)
    }

    @Test
    fun enforcementOffTracksReportWithoutChargingBudgetsAndTriggersAtThirtyMinutes() {
        val off = engine.setEnforcement(initial(), false, now + HOUR_MS, now, zone)
        val reported = engine.consumeOffUsage(off, ready, now, now + 30 * MINUTE_MS, zone)
        assertEquals(0, reported.dailyUsage.usedMs)
        assertEquals(30 * MINUTE_MS, reported.usageReports.single().offUsageMs)
        assertTrue(reported.offUsageReminder.firstNotificationSent)
        assertEquals(30 * MINUTE_MS, reported.offUsageReminder.qualifyingUsageMs)
    }

    @Test
    fun offReminderRepeatRequiresAnHourAndFifteenMoreUsageMinutes() {
        val off = engine.setEnforcement(initial(), false, null, now, zone)
        val first = engine.consumeOffUsage(off, ready, now, now + 30 * MINUTE_MS, zone)
        val fourteenMore = engine.consumeOffUsage(
            first,
            ready,
            now + 75 * MINUTE_MS,
            now + 89 * MINUTE_MS,
            zone,
        )
        assertEquals(first.offUsageReminder.lastNotificationWallMs, fourteenMore.offUsageReminder.lastNotificationWallMs)
        val fifteenMore = engine.consumeOffUsage(
            fourteenMore,
            ready,
            now + 90 * MINUTE_MS,
            now + 91 * MINUTE_MS,
            zone,
        )
        assertEquals(now + 91 * MINUTE_MS, fifteenMore.offUsageReminder.lastNotificationWallMs)
    }

    @Test
    fun offUsageReportSplitsAtLocalMidnight() {
        val start = ZonedDateTime.of(2026, 7, 10, 23, 59, 59, 500_000_000, zone)
            .toInstant().toEpochMilli()
        val off = engine.setEnforcement(
            engine.normalize(PersistedState(), start, zone),
            false,
            null,
            start,
            zone,
        )
        val reported = engine.consumeOffUsage(off, ready, start, start + 1_000, zone)
        assertEquals(500, reported.usageReports.first { it.localDayKey == "2026-07-10" }.offUsageMs)
        assertEquals(500, reported.usageReports.first { it.localDayKey == "2026-07-11" }.offUsageMs)
    }

    @Test
    fun resumedProtectionAllowsCurrentBrowserVisitUntilBrowserExits() {
        val off = engine.setEnforcement(initial(), false, null, now, zone)
        val resumed = engine.setEnforcement(
            off,
            true,
            null,
            now + 1_000,
            zone,
            allowCurrentVisit = true,
        )
        assertEquals(
            GateMode.ACCESS_GRANTED_CURRENT_VISIT,
            engine.underlyingMode(resumed, ready, now + 1_000),
        )
        val reported = engine.consumeCurrentVisitUsage(
            resumed,
            ready,
            now + 1_000,
            now + 11_000,
            zone,
        )
        assertEquals(0, reported.dailyUsage.usedMs)
        assertEquals(10_000, reported.usageReports.single().protectedUsageMs)
        val nextVisit = reported.copy(currentVisitGraceActive = false)
        assertEquals(GateMode.PAUSED, engine.underlyingMode(nextVisit, ready, now + 11_000))
    }

    @Test
    fun scheduleRecoveryEnablesProtectionAndPersistsCurrentVisitGrace() {
        val off = engine.setEnforcement(initial(), false, now + HOUR_MS, now, zone)

        val recovered = engine.reconcileProtectionSchedule(
            input = off,
            nowWallMs = now + HOUR_MS,
            zoneId = zone,
            currentVisitOpen = true,
        )

        assertTrue(recovered.settings.enforcementEnabled)
        assertNull(recovered.settings.pauseUntilWallMs)
        assertTrue(recovered.currentVisitGraceActive)
        assertEquals(GateMode.ACCESS_GRANTED_CURRENT_VISIT, engine.underlyingMode(recovered, ready, now + HOUR_MS))
    }

    @Test
    fun scheduleReconciliationLeavesNotYetDuePauseUntouched() {
        val off = engine.setEnforcement(initial(), false, now + HOUR_MS, now, zone)

        val unchanged = engine.reconcileProtectionSchedule(
            input = off,
            nowWallMs = now + 30 * MINUTE_MS,
            zoneId = zone,
            currentVisitOpen = true,
        )

        assertEquals(off, unchanged)
    }

    @Test
    fun currentVisitGraceSurvivesDisconnectAndClearsAfterConfirmedExit() {
        assertTrue(engine.retainCurrentVisitGrace(true, serviceConnected = false, browserActive = false))
        assertTrue(engine.retainCurrentVisitGrace(true, serviceConnected = true, browserActive = true))
        assertFalse(engine.retainCurrentVisitGrace(true, serviceConnected = true, browserActive = false))
    }

    @Test
    fun clockChangeRebasesReminderCooldown() {
        val reminder = OffUsageReminderState(
            sessionStartedAtWallMs = now - HOUR_MS,
            qualifyingUsageMs = 45 * MINUTE_MS,
            firstNotificationSent = true,
            lastNotificationWallMs = now - HOUR_MS,
            usageAtLastNotificationMs = 30 * MINUTE_MS,
        )
        val rebased = engine.rebaseOffUsageReminderAfterClockChange(
            reminder,
            protectionEnabled = false,
            nowWallMs = now + 2 * HOUR_MS,
        )
        assertEquals(now + 2 * HOUR_MS, rebased.lastNotificationWallMs)
        val off = engine.setEnforcement(initial(), false, null, now, zone).copy(
            offUsageReminder = rebased,
        )
        val afterFifteenMinutes = engine.consumeOffUsage(
            off,
            ready,
            now + 2 * HOUR_MS,
            now + 2 * HOUR_MS + 15 * MINUTE_MS,
            zone,
        )
        assertEquals(rebased.lastNotificationWallMs, afterFifteenMinutes.offUsageReminder.lastNotificationWallMs)
    }

    @Test
    fun reportRetentionKeepsTodayPlusThirtyHistoricalDays() {
        var state = engine.normalize(
            PersistedState(settings = RestrictorSettings(delayEnabled = false)),
            now - 31 * DAY_MS,
            zone,
        )
        repeat(32) { offset ->
            val start = now - (31 - offset) * DAY_MS
            state = engine.consumeUsage(state, ready, start, start + 1_000, zone)
        }
        assertEquals(31, state.usageReports.size)
        assertEquals("2026-06-10", state.usageReports.first().localDayKey)
        assertEquals("2026-07-10", state.usageReports.last().localDayKey)
    }

    @Test
    fun clearingReportsDoesNotResetBudgetsOrReminderCadence() {
        val reminder = OffUsageReminderState(
            sessionStartedAtWallMs = now,
            qualifyingUsageMs = 30 * MINUTE_MS,
            firstNotificationSent = true,
            lastNotificationWallMs = now + 30 * MINUTE_MS,
            usageAtLastNotificationMs = 30 * MINUTE_MS,
        )
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 12_000),
            usageReports = listOf(DailyUsageReport("2026-07-10", protectedUsageMs = 12_000)),
            offUsageReminder = reminder,
        )
        val cleared = engine.clearReportHistory(state)
        assertTrue(cleared.usageReports.isEmpty())
        assertEquals(12_000, cleared.dailyUsage.usedMs)
        assertEquals(reminder, cleared.offUsageReminder)
    }

    @Test
    fun cancellingActiveUnlockPreservesUsageAndOtherState() {
        val base = initial()
        val unlock = UnlockSession("normal", UnlockSource.NORMAL, now, now + 30 * MINUTE_MS)
        val report = DailyUsageReport("2026-07-10", protectedUsageMs = 12_000)
        val state = base.copy(
            dailyUsage = base.dailyUsage.copy(usedMs = 10_000),
            activeUnlock = unlock,
            dailyCounter = base.dailyCounter.copy(
                earnedBudgetRounds = 2,
                emergencySkipsUsed = 1,
            ),
            usageReports = listOf(report),
        )

        val cancelled = engine.cancelActiveUnlock(state, now + 1_000, zone)

        assertNull(cancelled.activeUnlock)
        assertEquals(10_000, cancelled.dailyUsage.usedMs)
        assertEquals(2, cancelled.dailyCounter.earnedBudgetRounds)
        assertEquals(1, cancelled.dailyCounter.emergencySkipsUsed)
        assertEquals(listOf(report), cancelled.usageReports)
        assertEquals(state.settings, cancelled.settings)
    }

    @Test
    fun normalizationDoesNotDeleteReportThatIsFutureInANewTimezone() {
        val state = initial().copy(
            usageReports = listOf(DailyUsageReport("2026-07-11", protectedUsageMs = 1_000)),
        )
        val normalized = engine.normalize(state, now, zone)
        assertEquals(state.usageReports, normalized.usageReports)
    }

    @Test
    fun offModeHealthStillReportsMissingAccessibility() {
        val state = engine.setEnforcement(initial(), false, null, now, zone)
        val snapshot = engine.snapshot(
            state,
            ready.copy(accessibilityEnabled = false, serviceConnected = false),
            now,
        )
        assertEquals(ProtectionHealth.ACCESSIBILITY_REQUIRED, snapshot.enforcementHealth)
    }

    @Test
    fun resetClearsAllRuntimeStateButKeepsRulesAndTheme() {
        val settings = RestrictorSettings(theme = ThemePreference.LIGHT, delayEnabled = false)
        val base = initial(settings)
        val dirty = base.copy(
            dailyUsage = base.dailyUsage.copy(usedMs = 3_000),
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, now, now + MINUTE_MS),
            dailyCounter = base.dailyCounter.copy(earnedBudgetRounds = 2, emergencySkipsUsed = 2),
            usageReports = listOf(DailyUsageReport("2026-07-10", protectedUsageMs = 3_000)),
            currentVisitGraceActive = true,
        )
        val reset = engine.resetUsage(dirty, now, zone)
        assertEquals(0, reset.dailyUsage.usedMs)
        assertNull(reset.activeUnlock)
        assertNull(reset.pendingWait)
        assertEquals(0, reset.dailyCounter.earnedBudgetRounds)
        assertEquals(0, reset.dailyCounter.emergencySkipsUsed)
        assertFalse(reset.settings.delayEnabled)
        assertEquals(ThemePreference.LIGHT, reset.settings.theme)
        assertEquals(dirty.usageReports, reset.usageReports)
        assertFalse(reset.currentVisitGraceActive)
    }

    @Test
    fun invalidSettingsCannotSave() {
        val settings = initial().settings.copy(emergencySkipDailyLimit = -1)
        assertFalse(engine.validateSettings(settings))
    }

    private fun applied(result: CommandResult): PersistedState = when (result) {
        is CommandResult.Applied -> result.state
        is CommandResult.NormalCompletionWon -> result.state
        is CommandResult.Rejected -> error("Expected applied, got ${result.reason}")
    }
}
