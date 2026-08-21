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
        assertEquals(1, settings.emergencySkipDailyLimit)
        assertEquals(listOf(3L, 5L, 10L), settings.sessionMinutes)
        assertEquals(ThemePreference.DARK, settings.theme)
        assertEquals(10, engine.budgetSnapshots(initial()).single().limitMinutes)
        assertEquals(12_000, WaitCalculator.requiredFocusMs(3))
    }

    @Test
    fun statePriorityStartsPaused() {
        assertEquals(GateMode.PAUSED, engine.underlyingMode(initial(), ready, now))
    }

    @Test
    fun exhaustedBudgetOutranksValidNormalUnlock() {
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 10 * MINUTE_MS),
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, now, now + HOUR_MS),
        )
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(state, ready, now))
    }

    @Test
    fun validEmergencyUnlockOutranksExhaustedBudget() {
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 10 * MINUTE_MS),
            activeUnlock = UnlockSession("unlock", UnlockSource.EMERGENCY, now, now + HOUR_MS),
        )
        assertEquals(GateMode.ACCESS_GRANTED_EMERGENCY, engine.underlyingMode(state, ready, now))
    }

    @Test
    fun normalizationForcesRequiredWaitOn() {
        val state = initial(RestrictorSettings(delayEnabled = false))

        assertTrue(state.settings.delayEnabled)
        assertEquals(GateMode.PAUSED, engine.underlyingMode(state, ready, now))
    }

    @Test
    fun baseBudgetExhaustionBlocksAndShowsFullBar() {
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 10 * MINUTE_MS),
        )
        val daily = engine.budgetSnapshots(state).first { it.period == BudgetPeriod.DAILY }
        assertTrue(daily.exhausted)
        assertEquals(100, daily.percentage)
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(state, ready, now))
    }

    @Test
    fun activeUnlockAccumulatesDailyUsage() {
        val state = initial().copy(
            activeUnlock = UnlockSession("active", UnlockSource.NORMAL, now, now + MINUTE_MS),
        )
        val used = engine.consumeUsage(state, ready, now, now + 10_000, zone)
        assertEquals(10_000, used.dailyUsage.usedMs)
    }

    @Test
    fun adjacentBrowserUsageIsKeptAsOneTimedActivity() {
        val state = initial().copy(
            activeUnlock = UnlockSession("active", UnlockSource.NORMAL, now, now + MINUTE_MS),
        )

        val first = engine.consumeUsage(state, ready, now, now + 2_000, zone)
        val second = engine.consumeUsage(first, ready, now + 2_000, now + 5_000, zone)

        val event = second.activityEvents.single()
        assertEquals(ActivityEventType.BROWSER_SESSION, event.type)
        assertEquals("2026-07-10", event.localDayKey)
        assertEquals(now, event.startedAtWallMs)
        assertEquals(now + 5_000, event.endedAtWallMs)
        assertEquals(5_000, event.valueMs)
    }

    @Test
    fun startWaitUsesSnapshotRevisionAndCreatesFixedCost() {
        val state = initial()
        val result = engine.startWait(state, ready, state.stateRevision, 5, now, zone)
        assertTrue(result is CommandResult.Applied)
        val pending = (result as CommandResult.Applied).state.pendingWait
        assertNotNull(pending)
        assertEquals(12_000, pending!!.requiredFocusMs)
        assertEquals(5 * MINUTE_MS, pending.selectedDurationMs)
    }

    @Test
    fun startWaitUsesCustomizedSessionPresets() {
        val state = initial(RestrictorSettings(sessionMinutes = listOf(1L, 45L, 120L)))

        val accepted = engine.startWait(state, ready, state.stateRevision, 45, now, zone)
        assertTrue(accepted is CommandResult.Applied)
        assertEquals(45 * MINUTE_MS, (accepted as CommandResult.Applied).state.pendingWait!!.selectedDurationMs)

        val rejected = engine.startWait(state, ready, state.stateRevision, 5, now, zone)
        assertTrue(rejected is CommandResult.Rejected)
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
        val earnedStart = applied(engine.earnPushupBudget(start, now, zone))
        val waiting = applied(engine.startWait(earnedStart, ready, earnedStart.stateRevision, 5, now, zone))
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
        assertEquals(1, skipped.dailyCounter.earnedBudgetRounds)
    }

    @Test
    fun emergencySkipAlwaysOffersFullThirtyMinutes() {
        val state = applied(engine.earnPushupBudget(initial(), now, zone))
        val snapshot = engine.snapshot(state, ready, now)
        assertEquals(30 * MINUTE_MS, snapshot.skipSnapshot.grantDurationMs)
        assertTrue(snapshot.skipSnapshot.eligible)
    }

    @Test
    fun emergencySkipStartsLockedAndFirstPushupSetEarnsExactlyOne() {
        val start = initial()
        val before = engine.snapshot(start, ready, now)
        assertEquals(0, before.skipSnapshot.remaining)
        assertFalse(before.skipSnapshot.eligible)

        val first = applied(engine.earnPushupBudget(start, now, zone))
        assertEquals(1, engine.snapshot(first, ready, now).skipSnapshot.remaining)

        val second = applied(engine.earnPushupBudget(first, now + 1_000, zone))
        assertEquals(1, engine.snapshot(second, ready, now + 1_000).skipSnapshot.remaining)
        assertEquals(30, engine.budgetSnapshots(second).single().limitMinutes)
    }

    @Test
    fun emergencySkipBypassesExhaustionAndConsumesCount() {
        val earned = applied(engine.earnPushupBudget(initial(), now, zone))
        val state = earned.copy(dailyUsage = earned.dailyUsage.copy(usedMs = 20 * MINUTE_MS))
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
            dailyUsage = initial().dailyUsage.copy(usedMs = 10 * MINUTE_MS),
        )
        val earned = applied(engine.earnPushupBudget(state, now, zone))
        assertNull(earned.activeUnlock)
        assertEquals(1, earned.dailyCounter.earnedBudgetRounds)
        assertEquals(0, earned.dailyCounter.emergencySkipsUsed)
        val budget = engine.budgetSnapshots(earned).single()
        assertEquals(20, budget.limitMinutes)
        assertEquals(10 * MINUTE_MS, budget.remainingMs)
        assertEquals(1, engine.snapshot(earned, ready, now).skipSnapshot.remaining)
        assertEquals(GateMode.PAUSED, engine.underlyingMode(earned, ready, now))
    }

    @Test
    fun pushupRoundCanIncreaseBudgetBeforeExhaustion() {
        val state = initial()
        val earned = applied(engine.earnPushupBudget(state, now, zone))
        assertNull(earned.activeUnlock)
        assertEquals(20, engine.budgetSnapshots(earned).single().limitMinutes)
    }

    @Test
    fun pushupRoundPreservesOrdinaryWaitInsteadOfOpeningBrowser() {
        val state = initial()
        val waiting = applied(
            engine.startWait(state, ready, state.stateRevision, 5, now, zone),
        )

        val earned = applied(engine.earnPushupBudget(waiting, now + 1_000, zone))

        assertEquals(waiting.pendingWait, earned.pendingWait)
        assertNull(earned.activeUnlock)
        assertEquals(GateMode.WAITING, engine.underlyingMode(earned, ready, now + 1_000))
    }

    @Test
    fun exhaustedBudgetCanUseEarnedEmergencySkip() {
        val base = initial()
        val earned = applied(engine.earnPushupBudget(base, now, zone))
        val state = earned.copy(dailyUsage = earned.dailyUsage.copy(usedMs = 20 * MINUTE_MS))
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
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 10 * MINUTE_MS - 500),
            activeUnlock = UnlockSession("active", UnlockSource.NORMAL, now, now + MINUTE_MS),
        )
        val used = engine.consumeUsage(state, ready, now, now + 2_000, zone)
        assertEquals(10 * MINUTE_MS, used.dailyUsage.usedMs)
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(used, ready, now + 2_000))
    }

    @Test
    fun emergencyUsageIsReportedWithoutCreatingFutureBudgetDebt() {
        val earnedBase = applied(engine.earnPushupBudget(initial(), now, zone))
        val base = earnedBase.copy(
            dailyUsage = earnedBase.dailyUsage.copy(usedMs = 20 * MINUTE_MS),
            activeUnlock = UnlockSession(
                id = "emergency",
                source = UnlockSource.EMERGENCY,
                grantedAtWallMs = now,
                expiresAtWallMs = now + 30 * MINUTE_MS,
            ),
        )
        val used = engine.consumeUsage(base, ready, now, now + 2_000, zone)
        assertEquals(20 * MINUTE_MS, used.dailyUsage.usedMs)
        assertEquals(
            2_000,
            used.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs,
        )
        assertEquals(GateMode.ACCESS_GRANTED_EMERGENCY, engine.underlyingMode(used, ready, now + 2_000))

        val afterEmergency = engine.cancelActiveUnlock(used, now + 2_000, zone)
        val earned = applied(engine.earnPushupBudget(afterEmergency, now + 2_000, zone))
        assertEquals(10 * MINUTE_MS, engine.budgetSnapshots(earned).single().remainingMs)
    }

    @Test
    fun earnedBudgetIsConsumedNormallyAndRemainsInReports() {
        val base = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 5 * MINUTE_MS),
        )
        val earned = applied(engine.earnPushupBudget(base, now, zone))
        val active = earned.copy(
            activeUnlock = UnlockSession("active", UnlockSource.NORMAL, now, now + MINUTE_MS),
        )
        val used = engine.consumeUsage(active, ready, now, now + 2_000, zone)
        assertEquals(5 * MINUTE_MS + 2_000, used.dailyUsage.usedMs)
        assertEquals(
            2_000,
            used.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs,
        )
        assertEquals(GateMode.ACCESS_GRANTED_NORMAL, engine.underlyingMode(used, ready, now + 2_000))
    }

    @Test
    fun activeUsageIsSplitAtDailyBoundary() {
        val start = ZonedDateTime.of(2026, 7, 10, 23, 59, 59, 500_000_000, zone).toInstant().toEpochMilli()
        val state = engine.normalize(PersistedState(), start, zone).copy(
            activeUnlock = UnlockSession("active", UnlockSource.NORMAL, start, start + 2_000),
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
            usageReports = listOf(DailyUsageReport("2026-07-10", pushupSessions = 3)),
        )
        val waiting = applied(
            engine.startWait(base, ready, base.stateRevision, 10, beforeMidnight, zone),
        )
        assertEquals(12_000, waiting.pendingWait!!.requiredFocusMs)
        val after = engine.normalize(waiting, beforeMidnight + 3_000, zone)
        assertEquals(12_000, after.pendingWait!!.requiredFocusMs)
        assertEquals(0, after.dailyCounter.earnedBudgetRounds)
        assertEquals(0, after.dailyCounter.emergencySkipsUsed)
        assertEquals(0, engine.snapshot(after, ready, beforeMidnight + 3_000).skipSnapshot.remaining)
    }

    @Test
    fun budgetResetDoesNotDeleteStillValidUnlock() {
        val before = ZonedDateTime.of(2026, 7, 10, 23, 59, 0, 0, zone).toInstant().toEpochMilli()
        val base = engine.normalize(PersistedState(), before, zone)
        val state = base.copy(
            dailyUsage = base.dailyUsage.copy(usedMs = 10 * MINUTE_MS),
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, before, before + 10 * MINUTE_MS),
        )
        val after = engine.normalize(state, before + 2 * MINUTE_MS, zone)
        assertEquals(0, after.dailyUsage.usedMs)
        assertNotNull(after.activeUnlock)
        assertEquals(GateMode.ACCESS_GRANTED_NORMAL, engine.underlyingMode(after, ready, before + 2 * MINUTE_MS))
    }

    @Test
    fun normalizationForcesProtectionAndEmergencySkipsOn() {
        val state = PersistedState(
            settings = RestrictorSettings(
                enforcementEnabled = false,
                pauseUntilWallMs = now + HOUR_MS,
                delayEnabled = false,
                emergencySkipsEnabled = false,
            ),
            currentVisitGraceActive = true,
            offUsageReminder = OffUsageReminderState(sessionStartedAtWallMs = now),
        )

        val normalized = engine.normalize(state, now, zone)

        assertTrue(normalized.settings.enforcementEnabled)
        assertNull(normalized.settings.pauseUntilWallMs)
        assertTrue(normalized.settings.delayEnabled)
        assertTrue(normalized.settings.emergencySkipsEnabled)
        assertFalse(normalized.currentVisitGraceActive)
        assertEquals(OffUsageReminderState(), normalized.offUsageReminder)
    }

    @Test
    fun successfulPushupSessionsPersistIntoMondayToSundayReports() {
        val friday = initial()
        val afterFirst = applied(engine.earnPushupBudget(friday, now, zone))
        val afterSecond = applied(engine.earnPushupBudget(afterFirst, now + 1_000, zone))

        assertEquals(2, afterSecond.dailyCounter.earnedBudgetRounds)
        assertEquals(2, afterSecond.usageReports.single().pushupSessions)
        assertEquals(
            listOf(ActivityEventType.PUSHUP_SET, ActivityEventType.PUSHUP_SET),
            afterSecond.activityEvents.map { it.type },
        )
        assertEquals(listOf(now, now + 1_000), afterSecond.activityEvents.map { it.startedAtWallMs })
        assertEquals(
            listOf(10 * MINUTE_MS, 10 * MINUTE_MS),
            afterSecond.activityEvents.map { it.valueMs },
        )

        val week = engine.snapshot(afterSecond, ready, now, zoneId = zone).currentWeekUsageReports
        assertEquals(
            listOf(
                "2026-07-06", "2026-07-07", "2026-07-08", "2026-07-09",
                "2026-07-10", "2026-07-11", "2026-07-12",
            ),
            week.map { it.localDayKey },
        )
        assertEquals(2, week.single { it.localDayKey == "2026-07-10" }.pushupSessions)
    }

    @Test
    fun normalizationArchivesLegacyCurrentDayPushupsBeforeMidnightReset() {
        val nextDay = now + DAY_MS
        val legacy = initial().copy(
            dailyCounter = DailyCounter("2026-07-10", earnedBudgetRounds = 3),
            usageReports = emptyList(),
        )

        val normalized = engine.normalize(legacy, nextDay, zone)

        assertEquals(3, normalized.usageReports.single { it.localDayKey == "2026-07-10" }.pushupSessions)
        assertEquals(0, normalized.dailyCounter.earnedBudgetRounds)
        assertEquals("2026-07-11", normalized.dailyCounter.localDayKey)
    }

    @Test
    fun reportRetentionKeepsTodayPlusThirtyHistoricalDays() {
        var state = engine.normalize(PersistedState(), now - 31 * DAY_MS, zone)
        repeat(32) { offset ->
            val start = now - (31 - offset) * DAY_MS
            state = state.copy(
                activeUnlock = UnlockSession("active-$offset", UnlockSource.NORMAL, start, start + 2_000),
            )
            state = engine.consumeUsage(state, ready, start, start + 1_000, zone)
        }
        assertEquals(31, state.usageReports.size)
        assertEquals("2026-06-10", state.usageReports.first().localDayKey)
        assertEquals("2026-07-10", state.usageReports.last().localDayKey)
    }

    @Test
    fun clearingReportsDoesNotResetBudgets() {
        val state = initial().copy(
            dailyUsage = initial().dailyUsage.copy(usedMs = 12_000),
            usageReports = listOf(
                DailyUsageReport("2026-07-10", protectedUsageMs = 12_000, pushupSessions = 2),
            ),
        )
        val cleared = engine.clearReportHistory(state)
        assertTrue(cleared.usageReports.isEmpty())
        assertEquals(12_000, cleared.dailyUsage.usedMs)
    }

    @Test
    fun cancellingActiveUnlockPreservesUsageAndOtherState() {
        val base = initial()
        val unlock = UnlockSession("normal", UnlockSource.NORMAL, now, now + 30 * MINUTE_MS)
        val report = DailyUsageReport(
            "2026-07-10",
            protectedUsageMs = 12_000,
            pushupSessions = 2,
        )
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
    fun invalidSettingsCannotSave() {
        val settings = initial().settings.copy(emergencySkipDailyLimit = -1)
        assertFalse(engine.validateSettings(settings))
        assertFalse(engine.validateSettings(initial().settings.copy(enforcementEnabled = false)))
        assertFalse(engine.validateSettings(initial().settings.copy(delayEnabled = false)))
        assertFalse(engine.validateSettings(initial().settings.copy(emergencySkipsEnabled = false)))
        assertFalse(engine.validateSettings(initial().settings.copy(sessionMinutes = listOf(0L, 5L, 10L))))
        assertTrue(engine.validateSettings(initial().settings.copy(sessionMinutes = listOf(3L, 3L, 10L))))
        assertFalse(engine.validateSettings(initial().settings.copy(sessionMinutes = listOf(3L, 5L, 121L))))
        assertTrue(engine.validateSettings(initial().settings.copy(sessionMinutes = listOf(1L, 45L, 120L))))
    }

    private fun applied(result: CommandResult): PersistedState = when (result) {
        is CommandResult.Applied -> result.state
        is CommandResult.NormalCompletionWon -> result.state
        is CommandResult.Rejected -> error("Expected applied, got ${result.reason}")
    }
}
