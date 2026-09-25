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
        activeTarget = ProtectedTarget.BROWSER_POOL,
        gateFocused = true,
        screenInteractive = true,
        keyguardUnlocked = true,
        serviceConnected = true,
        accessibilityEnabled = true,
        storageReady = true,
        gateSessionId = "gate-1",
    )
    private val youtubeReady = ready.copy(
        foregroundClass = ForegroundClass.OTHER,
        activeTarget = ProtectedTarget.YOUTUBE,
    )

    private fun initial(settings: RestrictorSettings = RestrictorSettings()): PersistedState =
        engine.normalize(PersistedState(settings = settings), now, zone)

    private fun initialWithBudget(settings: RestrictorSettings = RestrictorSettings()): PersistedState =
        applied(engine.earnPushupBudget(initial(settings), now, zone)).copy(activityEvents = emptyList())

    @Test
    fun emergencySkipReplacesAnExhaustedButUnexpiredNormalSession() {
        val earned = initialWithBudget()
        val exhausted = earned.copy(
            dailyUsage = earned.dailyUsage.copy(usedMs = 3 * MINUTE_MS),
            activeUnlock = UnlockSession("normal", UnlockSource.NORMAL, now, now + 10 * MINUTE_MS),
        )
        val snapshot = engine.snapshot(exhausted, ready, now, zoneId = zone)
        assertEquals(GateMode.OUT_OF_TIME, snapshot.canonicalMode)
        assertTrue(snapshot.skipSnapshot.eligible)
        val result = engine.emergencySkip(exhausted, ready, snapshot.stateRevision, null, now, zone)
        assertEquals(UnlockSource.EMERGENCY, (result as CommandResult.Applied).state.activeUnlock?.source)
    }

    @Test
    fun monthlyCreditUsesTheSuppliedZoneInsteadOfPrague() {
        val beforeUtcNinth = ZonedDateTime.of(2026, 9, 8, 23, 0, 0, 0, zone).toInstant().toEpochMilli()
        val result = engine.recordPushupRepetitions(PersistedState(), 5, beforeUtcNinth, zone) as CommandResult.Applied
        assertEquals(MonthlyPushupCycle("2026-08-09", 5), result.state.monthlyPushupCycles.single())
    }

    @Test
    fun defaultsUseFixedDailyBudgetAndWaitRules() {
        val settings = RestrictorSettings()
        assertTrue(settings.enforcementEnabled)
        assertTrue(settings.delayEnabled)
        assertEquals(1, settings.emergencySkipDailyLimit)
        assertEquals(listOf(3L, 5L, 10L), settings.sessionMinutes)
        assertEquals(ThemePreference.DARK, settings.theme)
        assertEquals(0, engine.budgetSnapshots(initial()).single().limitMinutes)
    }

    @Test
    fun statePriorityStartsOutOfTime() {
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(initial(), ready, now))
    }

    @Test
    fun youtubeStaysLockedUntilTwentyAccumulatedPushups() {
        val emergency = initial().copy(
            activeUnlock = UnlockSession("emergency", UnlockSource.EMERGENCY, now, now + HOUR_MS),
        )
        assertEquals(GateMode.YOUTUBE_LOCKED, engine.underlyingMode(emergency, youtubeReady, now))

        val partial = applied(engine.recordPushupRepetitions(initial(), 19, now, zone))
        assertEquals(GateMode.YOUTUBE_LOCKED, engine.underlyingMode(partial, youtubeReady, now))

        val earned = applied(engine.recordPushupRepetitions(partial, 1, now + 1_000, zone))
        val snapshot = engine.snapshot(earned, youtubeReady, now + 1_000)
        assertEquals(20, snapshot.verifiedPushupsToday)
        assertEquals(4, snapshot.earnedBudgetRoundsToday)
        assertEquals(GateMode.YOUTUBE_ACCESS_GRANTED, snapshot.canonicalMode)
        assertEquals(ProtectedTarget.YOUTUBE, snapshot.activeTarget)
        assertFalse(snapshot.usesFullGate)
        assertFalse(snapshot.usesBadge)
    }

    @Test
    fun youtubeUnlockSurvivesEveningResetButEndsAtFourAm() {
        val beforeEveningReset = ZonedDateTime.of(2026, 7, 10, 19, 59, 59, 0, zone)
            .toInstant()
            .toEpochMilli()
        val earnedBeforeEvening = applied(engine.recordPushupRepetitions(
            engine.normalize(PersistedState(), beforeEveningReset, zone),
            20,
            beforeEveningReset,
            zone,
        ))
        val afterEvening = engine.normalize(earnedBeforeEvening, beforeEveningReset + 1_000, zone)
        assertEquals(GateMode.YOUTUBE_ACCESS_GRANTED, engine.underlyingMode(
            afterEvening,
            youtubeReady,
            beforeEveningReset + 1_000,
        ))

        val beforeDailyReset = ZonedDateTime.of(2026, 7, 11, 3, 59, 59, 0, zone)
            .toInstant()
            .toEpochMilli()
        val earnedBeforeDaily = applied(engine.recordPushupRepetitions(
            engine.normalize(PersistedState(), beforeDailyReset, zone),
            20,
            beforeDailyReset,
            zone,
        ))
        val afterDaily = engine.normalize(earnedBeforeDaily, beforeDailyReset + 1_000, zone)
        assertEquals(0, afterDaily.dailyCounter.earnedBudgetRounds)
        assertEquals(GateMode.YOUTUBE_LOCKED, engine.underlyingMode(
            afterDaily,
            youtubeReady,
            beforeDailyReset + 1_000,
        ))
    }

    @Test
    fun youtubeAccessDoesNotConsumeBrowserBudget() {
        val earned = applied(engine.recordPushupRepetitions(initial(), 20, now, zone))
        val after = engine.consumeUsage(earned, youtubeReady, now, now + MINUTE_MS, zone)

        assertEquals(0, after.dailyUsage.usedMs)
        assertEquals(0, after.usageReports.single().protectedUsageMs)
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
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(state, ready, now))
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
        val state = initialWithBudget().copy(
            activeUnlock = UnlockSession("active", UnlockSource.NORMAL, now, now + MINUTE_MS),
        )
        val used = engine.consumeUsage(state, ready, now, now + 10_000, zone)
        assertEquals(10_000, used.dailyUsage.usedMs)
    }

    @Test
    fun adjacentBrowserUsageIsKeptAsOneTimedActivity() {
        val state = initialWithBudget().copy(
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
        val state = initialWithBudget()
        val result = engine.startWait(state, ready, state.stateRevision, 5, now, zone)
        assertTrue(result is CommandResult.Applied)
        val pending = (result as CommandResult.Applied).state.pendingWait
        assertNotNull(pending)
        assertEquals(12_000, pending!!.requiredFocusMs)
        assertEquals(5 * MINUTE_MS, pending.selectedDurationMs)
    }

    @Test
    fun startWaitUsesCustomizedSessionPresets() {
        val state = initialWithBudget(RestrictorSettings(sessionMinutes = listOf(1L, 45L, 120L)))

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
        val state = initialWithBudget()
        val started = applied(engine.startWait(state, ready, state.stateRevision, 10, now, zone))
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
        val start = initialWithBudget()
        val waiting = applied(engine.startWait(start, ready, start.stateRevision, 10, now, zone))
        val completed = applied(
            engine.advanceWait(waiting, ready, 12_000, now + 12_000, zone, "gate-1"),
        )
        assertNull(completed.pendingWait)
        assertEquals(UnlockSource.NORMAL, completed.activeUnlock!!.source)
        assertEquals(1, completed.dailyCounter.earnedBudgetRounds)

        val duplicate = engine.completeNormalWait(
            completed,
            ready,
            now + 12_100,
            zone,
            waiting.pendingWait!!.id,
            "gate-1",
        )
        assertTrue(duplicate is CommandResult.Rejected)
        assertEquals(1, (duplicate as CommandResult.Rejected).state.dailyCounter.earnedBudgetRounds)
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
    fun emergencySkipAlwaysOffersFullFifteenMinutes() {
        val state = applied(engine.earnPushupBudget(initial(), now, zone))
        val snapshot = engine.snapshot(state, ready, now)
        assertEquals(15 * MINUTE_MS, snapshot.skipSnapshot.grantDurationMs)
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
        assertEquals(6, engine.budgetSnapshots(second).single().limitMinutes)
    }

    @Test
    fun emergencySkipNeedsARemainingDailySkipAGateModeAndNoCurrentUnlock() {
        val earned = initialWithBudget()
        assertTrue(engine.snapshot(earned, ready, now).skipSnapshot.eligible)

        // The daily limit is one skip, so after one use nothing remains even though budget was earned.
        val used = earned.copy(dailyCounter = earned.dailyCounter.copy(emergencySkipsUsed = 1))
        val usedSkip = engine.snapshot(used, ready, now).skipSnapshot
        assertEquals(0, usedSkip.remaining)
        assertFalse(usedSkip.eligible)

        // YouTube modes are not gate modes that offer a skip.
        assertFalse(engine.snapshot(earned, youtubeReady, now).skipSnapshot.eligible)

        // The out-of-time latch is a gate mode, but a current emergency session still blocks a second skip.
        val latched = ready.copy(outOfTimeLatchToken = "latch")
        assertTrue(engine.snapshot(earned, latched, now).skipSnapshot.eligible)
        val emergency = earned.copy(
            activeUnlock = UnlockSession("emergency", UnlockSource.EMERGENCY, now, now + 15 * MINUTE_MS),
        )
        val latchedEmergency = engine.snapshot(emergency, latched, now)
        assertEquals(GateMode.OUT_OF_TIME_LATCHED, latchedEmergency.canonicalMode)
        assertFalse(latchedEmergency.skipSnapshot.eligible)
    }

    @Test
    fun emergencySkipBypassesExhaustionAndConsumesCount() {
        val earned = applied(engine.earnPushupBudget(initial(), now, zone))
        val state = earned.copy(dailyUsage = earned.dailyUsage.copy(usedMs = 3 * MINUTE_MS))
        val skipped = applied(
            engine.emergencySkip(state, ready, state.stateRevision, null, now, zone),
        )
        assertEquals(UnlockSource.EMERGENCY, skipped.activeUnlock!!.source)
        assertEquals(now + 15 * MINUTE_MS, skipped.activeUnlock.expiresAtWallMs)
        assertEquals(1, skipped.dailyCounter.emergencySkipsUsed)
        assertEquals(GateMode.ACCESS_GRANTED_EMERGENCY, engine.underlyingMode(skipped, ready, now))
    }

    @Test
    fun pushupRoundAddsBudgetButDoesNotUnlockBrowser() {
        val state = initial()
        val earned = applied(engine.earnPushupBudget(state, now, zone))
        assertNull(earned.activeUnlock)
        assertEquals(1, earned.dailyCounter.earnedBudgetRounds)
        assertEquals(0, earned.dailyCounter.emergencySkipsUsed)
        val budget = engine.budgetSnapshots(earned).single()
        assertEquals(3, budget.limitMinutes)
        assertEquals(3 * MINUTE_MS, budget.remainingMs)
        assertEquals(1, engine.snapshot(earned, ready, now).skipSnapshot.remaining)
        assertEquals(GateMode.PAUSED, engine.underlyingMode(earned, ready, now))
    }

    @Test
    fun pushupRoundCanIncreaseBudgetBeforeExhaustion() {
        val state = initial()
        val earned = applied(engine.earnPushupBudget(state, now, zone))
        assertNull(earned.activeUnlock)
        assertEquals(3, engine.budgetSnapshots(earned).single().limitMinutes)
    }

    @Test
    fun partialPushupsCarryAcrossVideosUntilTheyCompleteAReward() {
        val four = applied(engine.recordPushupRepetitions(initial(), 4, now, zone))
        assertEquals(0, four.dailyCounter.earnedBudgetRounds)
        assertEquals(24, four.dailyCounter.exercisePointSixths)
        assertEquals(0, engine.budgetSnapshots(four).single().limitMinutes)
        assertEquals(0, engine.snapshot(four, ready, now).skipSnapshot.remaining)
        assertTrue(four.activityEvents.isEmpty())
        assertEquals(4, four.monthlyPushupCycles.single().totalPushups)

        val five = applied(engine.recordPushupRepetitions(four, 1, now + 1_000, zone))
        assertEquals(1, five.dailyCounter.earnedBudgetRounds)
        assertEquals(0, five.dailyCounter.exercisePointSixths)
        assertEquals(3, engine.budgetSnapshots(five).single().limitMinutes)
        assertEquals(1, engine.snapshot(five, ready, now + 1_000).skipSnapshot.remaining)
        assertEquals(3 * MINUTE_MS, five.activityEvents.single().valueMs)
        assertEquals(5, five.monthlyPushupCycles.single().totalPushups)
    }

    @Test
    fun monthlyPushupsRollOverOnPragueNinthWithoutChangingCompletedTotal() {
        val prague = ZoneId.of("Europe/Prague")
        val beforeBoundary = ZonedDateTime.of(2026, 9, 8, 23, 59, 59, 0, prague)
            .toInstant()
            .toEpochMilli()
        val afterBoundary = beforeBoundary + 2_000
        val first = applied(engine.recordPushupRepetitions(
            engine.normalize(PersistedState(), beforeBoundary, prague),
            4,
            beforeBoundary,
            prague,
        ))

        val second = applied(engine.recordPushupRepetitions(first, 1, afterBoundary, prague))
        val snapshot = engine.snapshot(second, ready, afterBoundary, zoneId = prague)

        assertEquals(MonthlyPushupCycle("2026-08-09", 4), second.monthlyPushupCycles[0])
        assertEquals(MonthlyPushupCycle("2026-09-09", 1), second.monthlyPushupCycles[1])
        assertEquals(1, snapshot.currentMonthlyPushupCycle.totalPushups)
        assertEquals(listOf(MonthlyPushupCycle("2026-08-09", 4)), snapshot.completedMonthlyPushupCycles)
    }

    @Test
    fun oneVideoAndCombinedVideosCanEarnMultipleRewards() {
        val combined = applied(engine.recordPushupRepetitions(initial(), 4, now, zone))
        val combinedTen = applied(engine.recordPushupRepetitions(combined, 6, now + 1_000, zone))
        assertEquals(2, combinedTen.dailyCounter.earnedBudgetRounds)
        assertEquals(0, combinedTen.dailyCounter.exercisePointSixths)
        assertEquals(6, engine.budgetSnapshots(combinedTen).single().limitMinutes)
        assertEquals(6 * MINUTE_MS, combinedTen.activityEvents.single().valueMs)

        val twelve = applied(engine.recordPushupRepetitions(initial(), 12, now, zone))
        assertEquals(2, twelve.dailyCounter.earnedBudgetRounds)
        assertEquals(12, twelve.dailyCounter.exercisePointSixths)
        assertEquals(6, engine.budgetSnapshots(twelve).single().limitMinutes)
    }

    @Test
    fun eveningPreservesBudgetUsageAndNormalAccess() {
        val beforeReset = ZonedDateTime.of(2026, 7, 10, 19, 59, 59, 0, zone)
            .toInstant()
            .toEpochMilli()
        val earned = applied(engine.recordPushupRepetitions(
            engine.normalize(PersistedState(), beforeReset, zone),
            19,
            beforeReset,
            zone,
        ))
        val report = earned.usageReports.single()
        val state = earned.copy(
            dailyUsage = earned.dailyUsage.copy(usedMs = 5 * MINUTE_MS),
            activeUnlock = UnlockSession("normal", UnlockSource.NORMAL, beforeReset, beforeReset + MINUTE_MS),
        )

        val after = engine.normalize(state, beforeReset + 1_000, zone)

        assertEquals(3, after.dailyCounter.earnedBudgetRounds)
        assertEquals(3, after.dailyCounter.spendableBudgetRounds)
        assertEquals(24, after.dailyCounter.exercisePointSixths)
        assertFalse(after.dailyCounter.legacyEveningResetApplied)
        assertEquals(5 * MINUTE_MS, after.dailyUsage.usedMs)
        assertEquals(state.activeUnlock, after.activeUnlock)
        assertEquals(report, after.usageReports.single())
        assertEquals(1, engine.snapshot(after, ready, beforeReset + 1_000).skipSnapshot.remaining)
        assertEquals(GateMode.ACCESS_GRANTED_NORMAL, engine.underlyingMode(after, ready, beforeReset + 1_000))
    }

    @Test
    fun expiredUnlockRecordDoesNotHideEarnedEmergencySkip() {
        val base = initial()
        val state = base.copy(
            dailyCounter = base.dailyCounter.copy(
                earnedBudgetRounds = 1,
                spendableBudgetRounds = 0,
            ),
            activeUnlock = UnlockSession(
                id = "expired-normal",
                source = UnlockSource.NORMAL,
                grantedAtWallMs = now - 2 * MINUTE_MS,
                expiresAtWallMs = now - MINUTE_MS,
            ),
        )

        val snapshot = engine.snapshot(state, ready, now)

        assertEquals(GateMode.OUT_OF_TIME, snapshot.canonicalMode)
        assertTrue(snapshot.skipSnapshot.eligible)
        assertEquals(1, snapshot.skipSnapshot.remaining)
    }

    @Test
    fun normalUsageContinuesAcrossTwentyHundred() {
        val start = ZonedDateTime.of(2026, 7, 10, 19, 59, 59, 500_000_000, zone)
            .toInstant()
            .toEpochMilli()
        val earned = applied(engine.earnPushupBudget(
            engine.normalize(PersistedState(), start, zone),
            start,
            zone,
        ))
        val state = earned.copy(
            activeUnlock = UnlockSession("normal", UnlockSource.NORMAL, start, start + MINUTE_MS),
        )

        val after = engine.consumeUsage(state, ready, start, start + 1_000, zone)

        assertEquals(1000, after.dailyUsage.usedMs)
        assertEquals(1000, after.usageReports.single().protectedUsageMs)
        assertEquals(state.activeUnlock, after.activeUnlock)
        assertEquals(3, engine.budgetSnapshots(after).single().limitMinutes)
    }

    @Test
    fun eveningPreservesPartialProgressForNewPushups() {
        val reset = ZonedDateTime.of(2026, 7, 10, 20, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val before = applied(engine.recordPushupRepetitions(
            engine.normalize(PersistedState(), reset - 1_000, zone),
            4,
            reset - 1_000,
            zone,
        ))
        val resetState = engine.normalize(before, reset, zone)

        val afterPushup = applied(engine.recordPushupRepetitions(resetState, 1, reset + 1_000, zone))
        val later = engine.normalize(afterPushup, reset + HOUR_MS, zone)

        assertEquals(1, later.dailyCounter.earnedBudgetRounds)
        assertEquals(1, later.dailyCounter.spendableBudgetRounds)
        assertEquals(0, later.dailyCounter.exercisePointSixths)
        assertEquals(3, engine.budgetSnapshots(later).single().limitMinutes)
    }

    @Test
    fun eveningPreservesEmergencyUnlockAndPendingWait() {
        val reset = ZonedDateTime.of(2026, 7, 10, 20, 0, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val earned = applied(engine.earnPushupBudget(
            engine.normalize(PersistedState(), reset - MINUTE_MS, zone),
            reset - MINUTE_MS,
            zone,
        ))
        val waiting = applied(engine.startWait(
            earned,
            ready,
            earned.stateRevision,
            3,
            reset - 1_000,
            zone,
        ))
        val cancelledWait = engine.normalize(waiting, reset, zone)
        assertEquals(waiting.pendingWait, cancelledWait.pendingWait)

        val emergency = earned.copy(
            activeUnlock = UnlockSession(
                "emergency",
                UnlockSource.EMERGENCY,
                reset - MINUTE_MS,
                reset + 14 * MINUTE_MS,
            ),
        )
        val preserved = engine.normalize(emergency, reset, zone)

        assertEquals(UnlockSource.EMERGENCY, preserved.activeUnlock!!.source)
        assertEquals(GateMode.ACCESS_GRANTED_EMERGENCY, engine.underlyingMode(preserved, ready, reset))
    }

    @Test
    fun pushupRoundPreservesOrdinaryWaitInsteadOfOpeningBrowser() {
        val state = initialWithBudget()
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
        val state = earned.copy(dailyUsage = earned.dailyUsage.copy(usedMs = 3 * MINUTE_MS))
        val snapshot = engine.snapshot(state, ready, now)
        assertEquals(GateMode.OUT_OF_TIME, snapshot.canonicalMode)
        assertTrue(snapshot.skipSnapshot.eligible)
    }

    @Test
    fun normalCompletionWinsSkipRace() {
        val state = initialWithBudget()
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
        assertEquals(1, completed.dailyCounter.earnedBudgetRounds)
        assertEquals(0, completed.dailyCounter.emergencySkipsUsed)
    }

    @Test
    fun usageStopsExactlyAtTightestBudget() {
        val budgeted = initialWithBudget()
        val state = budgeted.copy(
            dailyUsage = budgeted.dailyUsage.copy(usedMs = 3 * MINUTE_MS - 500),
            activeUnlock = UnlockSession("active", UnlockSource.NORMAL, now, now + MINUTE_MS),
        )
        val used = engine.consumeUsage(state, ready, now, now + 2_000, zone)
        assertEquals(3 * MINUTE_MS, used.dailyUsage.usedMs)
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(used, ready, now + 2_000))
    }

    @Test
    fun emergencyAccessDoesNotConsumeEarnedBudget() {
        var state = initial()
        repeat(5) {
            state = applied(engine.earnPushupBudget(state, now, zone))
        }
        assertEquals(15 * MINUTE_MS, engine.budgetSnapshots(state).single().remainingMs)
        assertEquals(0L, state.dailyUsage.usedMs)

        val emergency = state.copy(
            activeUnlock = UnlockSession(
                id = "emergency",
                source = UnlockSource.EMERGENCY,
                grantedAtWallMs = now,
                expiresAtWallMs = now + 15 * MINUTE_MS,
            ),
        )

        val used = engine.consumeUsage(emergency, ready, now, now + 10 * MINUTE_MS, zone)

        assertEquals(0L, used.dailyUsage.usedMs)
        assertEquals(15 * MINUTE_MS, engine.budgetSnapshots(used).single().remainingMs)

        val todayReport = used.usageReports.single { it.localDayKey == "2026-07-10" }
        assertEquals(10 * MINUTE_MS, todayReport.protectedUsageMs)
        val browserSessions = used.activityEvents.filter { it.type == ActivityEventType.BROWSER_SESSION }
        assertEquals(10 * MINUTE_MS, browserSessions.sumOf { it.valueMs })
    }

    @Test
    fun earnExerciseCreditMidWindowRemainsUnspentDuringEmergencyAccess() {
        val baseState = initial()
        assertEquals(0L, engine.budgetSnapshots(baseState).single().remainingMs)

        val emergency = baseState.copy(
            activeUnlock = UnlockSession(
                id = "emergency",
                source = UnlockSource.EMERGENCY,
                grantedAtWallMs = now,
                expiresAtWallMs = now + 15 * MINUTE_MS,
            ),
        )

        val used1 = engine.consumeUsage(emergency, ready, now, now + 5 * MINUTE_MS, zone)
        assertEquals(0L, used1.dailyUsage.usedMs)
        assertEquals(5 * MINUTE_MS, used1.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs)

        val earnedMidWindow = applied(engine.earnPushupBudget(used1, now + 5 * MINUTE_MS, zone))
        assertEquals(3 * MINUTE_MS, engine.budgetSnapshots(earnedMidWindow).single().remainingMs)
        assertEquals(1L, earnedMidWindow.dailyCounter.spendableBudgetRounds)

        val used2 = engine.consumeUsage(earnedMidWindow, ready, now + 5 * MINUTE_MS, now + 10 * MINUTE_MS, zone)

        assertEquals(0L, used2.dailyUsage.usedMs)
        assertEquals(3 * MINUTE_MS, engine.budgetSnapshots(used2).single().remainingMs)
        assertEquals(10 * MINUTE_MS, used2.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs)
    }

    @Test
    fun emergencyUsageBoundaryEndingExactlyAtExpiryCrossingExpiryAndStartingAtExpiry() {
        var state = initial()
        repeat(5) {
            state = applied(engine.earnPushupBudget(state, now, zone))
        }
        val expiry = now + 15 * MINUTE_MS
        val emergency = state.copy(
            activeUnlock = UnlockSession(
                id = "emergency",
                source = UnlockSource.EMERGENCY,
                grantedAtWallMs = now,
                expiresAtWallMs = expiry,
            ),
        )

        // 1. Ending exactly at expiry [now, expiry)
        val exactEnd = engine.consumeUsage(emergency, ready, now, expiry, zone)
        assertEquals(0L, exactEnd.dailyUsage.usedMs)
        assertEquals(15 * MINUTE_MS, engine.budgetSnapshots(exactEnd).single().remainingMs)
        assertEquals(15 * MINUTE_MS, exactEnd.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs)
        assertEquals(expiry, exactEnd.runtimeMetadata.lastCommittedWallMs)

        // 2. Crossing expiry: [now, expiry + 5m) => only 15m is accessible/free, 5m past expiry is unauthorized and neither charged nor reported
        val crossing = engine.consumeUsage(emergency, ready, now, expiry + 5 * MINUTE_MS, zone)
        assertEquals(0L, crossing.dailyUsage.usedMs)
        assertEquals(15 * MINUTE_MS, engine.budgetSnapshots(crossing).single().remainingMs)
        assertEquals(15 * MINUTE_MS, crossing.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs)
        assertEquals(expiry, crossing.runtimeMetadata.lastCommittedWallMs)
        assertNull(crossing.activeUnlock)
        assertEquals(GateMode.PAUSED, engine.underlyingMode(crossing, ready, expiry + 5 * MINUTE_MS))

        // 3. Starting at expiry: [expiry, expiry + 5m) => unauthorized, zero accessible, returns unchanged
        val startingAtExpiry = engine.consumeUsage(emergency, ready, expiry, expiry + 5 * MINUTE_MS, zone)
        assertEquals(0L, startingAtExpiry.dailyUsage.usedMs)
        assertEquals(15 * MINUTE_MS, engine.budgetSnapshots(startingAtExpiry).single().remainingMs)
        assertTrue(startingAtExpiry.usageReports.isEmpty() || startingAtExpiry.usageReports.single().protectedUsageMs == 0L)
    }

    @Test
    fun emergencyUsageAcrossTwentyHundredPreservesFreeAccessAndBudget() {
        val eveningReset = ZonedDateTime.of(2026, 7, 10, 20, 0, 0, 0, zone).toInstant().toEpochMilli()
        val start = eveningReset - 5 * MINUTE_MS
        val expiry = eveningReset + 10 * MINUTE_MS
        val end = eveningReset + 5 * MINUTE_MS

        var base = engine.normalize(PersistedState(), start, zone)
        repeat(5) {
            base = applied(engine.earnPushupBudget(base, start, zone))
        }
        assertEquals(15 * MINUTE_MS, engine.budgetSnapshots(base).single().remainingMs)

        val emergency = base.copy(
            activeUnlock = UnlockSession(
                id = "emergency",
                source = UnlockSource.EMERGENCY,
                grantedAtWallMs = start,
                expiresAtWallMs = expiry,
            ),
        )

        val used = engine.consumeUsage(emergency, ready, start, end, zone)

        // Emergency use remains free across the evening; the earned budget stays available.
        assertEquals(0L, used.dailyUsage.usedMs)
        assertEquals(5L, used.dailyCounter.spendableBudgetRounds)
        assertFalse(used.dailyCounter.legacyEveningResetApplied)
        assertNotNull(used.activeUnlock)
        assertEquals(UnlockSource.EMERGENCY, used.activeUnlock?.source)
        assertEquals(10 * MINUTE_MS, used.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs)
    }

    @Test
    fun emergencyUsageSpanningDailyResetAttributesUsageToBothDaysWithoutCharge() {
        val dailyReset = ZonedDateTime.of(2026, 7, 11, 4, 0, 0, 0, zone).toInstant().toEpochMilli()
        val start = dailyReset - 5 * MINUTE_MS
        val expiry = dailyReset + 10 * MINUTE_MS
        val end = dailyReset + 5 * MINUTE_MS

        val base = engine.normalize(PersistedState(), start, zone)
        val emergency = base.copy(
            activeUnlock = UnlockSession(
                id = "emergency",
                source = UnlockSource.EMERGENCY,
                grantedAtWallMs = start,
                expiresAtWallMs = expiry,
            ),
        )

        val used = engine.consumeUsage(emergency, ready, start, end, zone)

        assertEquals(0L, used.dailyUsage.usedMs)
        assertEquals("2026-07-11", used.dailyUsage.key)
        assertEquals(5 * MINUTE_MS, used.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs)
        assertEquals(5 * MINUTE_MS, used.usageReports.single { it.localDayKey == "2026-07-11" }.protectedUsageMs)
        assertNotNull(used.activeUnlock)
        assertEquals(UnlockSource.EMERGENCY, used.activeUnlock?.source)
    }

    @Test
    fun emergencyUsageIsReportedWithoutCreatingFutureBudgetDebt() {
        val earnedBase = applied(engine.earnPushupBudget(initial(), now, zone))
        val base = earnedBase.copy(
            dailyUsage = earnedBase.dailyUsage.copy(usedMs = 3 * MINUTE_MS),
            activeUnlock = UnlockSession(
                id = "emergency",
                source = UnlockSource.EMERGENCY,
                grantedAtWallMs = now,
                expiresAtWallMs = now + 15 * MINUTE_MS,
            ),
        )
        val used = engine.consumeUsage(base, ready, now, now + 2_000, zone)
        assertEquals(3 * MINUTE_MS, used.dailyUsage.usedMs)
        assertEquals(
            2_000,
            used.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs,
        )
        assertEquals(GateMode.ACCESS_GRANTED_EMERGENCY, engine.underlyingMode(used, ready, now + 2_000))

        val afterEmergency = engine.cancelActiveUnlock(used, now + 2_000, zone)
        val earned = applied(engine.earnPushupBudget(afterEmergency, now + 2_000, zone))
        assertEquals(3 * MINUTE_MS, engine.budgetSnapshots(earned).single().remainingMs)
    }

    @Test
    fun earnedBudgetIsConsumedNormallyAndRemainsInReports() {
        val base = initial().copy(dailyUsage = initial().dailyUsage.copy(usedMs = MINUTE_MS))
        val earned = applied(engine.earnPushupBudget(base, now, zone))
        val active = earned.copy(
            activeUnlock = UnlockSession("active", UnlockSource.NORMAL, now, now + MINUTE_MS),
        )
        val used = engine.consumeUsage(active, ready, now, now + 2_000, zone)
        assertEquals(MINUTE_MS + 2_000, used.dailyUsage.usedMs)
        assertEquals(
            2_000,
            used.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs,
        )
        assertEquals(GateMode.ACCESS_GRANTED_NORMAL, engine.underlyingMode(used, ready, now + 2_000))
    }

    @Test
    fun activeUsageIsSplitAtDailyBoundary() {
        val start = ZonedDateTime.of(2026, 7, 11, 3, 59, 59, 500_000_000, zone).toInstant().toEpochMilli()
        val state = applied(
            engine.earnPushupBudget(engine.normalize(PersistedState(), start, zone), start, zone),
        ).copy(
            activeUnlock = UnlockSession("active", UnlockSource.NORMAL, start, start + 2_000),
        )
        val used = engine.consumeUsage(state, ready, start, start + 1_000, zone)
        assertEquals("2026-07-11", used.dailyUsage.key)
        assertEquals(0, used.dailyUsage.usedMs)
    }

    @Test
    fun pendingWaitStaysFixedAcrossFourAmButEarnedBudgetResets() {
        val beforeReset = ZonedDateTime.of(2026, 7, 11, 3, 59, 58, 0, zone).toInstant().toEpochMilli()
        val base = engine.normalize(PersistedState(), beforeReset, zone).copy(
            dailyCounter = DailyCounter(
                "2026-07-10",
                earnedBudgetRounds = 3,
                exercisePointSixths = 24,
                spendableBudgetRounds = 3,
                legacyEveningResetApplied = true,
            ),
            usageReports = listOf(DailyUsageReport("2026-07-10", exerciseSets = 3)),
        )
        val waiting = applied(
            engine.startWait(base, ready, base.stateRevision, 10, beforeReset, zone),
        )
        assertEquals(12_000, waiting.pendingWait!!.requiredFocusMs)
        val after = engine.normalize(waiting, beforeReset + 3_000, zone)
        assertEquals(12_000, after.pendingWait!!.requiredFocusMs)
        assertEquals(0, after.dailyCounter.earnedBudgetRounds)
        assertEquals(0, after.dailyCounter.spendableBudgetRounds)
        assertEquals(0, after.dailyCounter.emergencySkipsUsed)
        assertEquals(0, after.dailyCounter.exercisePointSixths)
        assertFalse(after.dailyCounter.legacyEveningResetApplied)
        assertEquals(0, engine.snapshot(after, ready, beforeReset + 3_000).skipSnapshot.remaining)
    }

    @Test
    fun budgetResetDoesNotDeleteStillValidUnlock() {
        val before = ZonedDateTime.of(2026, 7, 11, 3, 59, 0, 0, zone).toInstant().toEpochMilli()
        val base = engine.normalize(PersistedState(), before, zone)
        val state = base.copy(
            dailyUsage = base.dailyUsage.copy(usedMs = 10 * MINUTE_MS),
            activeUnlock = UnlockSession("unlock", UnlockSource.NORMAL, before, before + 10 * MINUTE_MS),
        )
        val after = engine.normalize(state, before + 2 * MINUTE_MS, zone)
        assertEquals(0, after.dailyUsage.usedMs)
        assertNotNull(after.activeUnlock)
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(after, ready, before + 2 * MINUTE_MS))
    }

    @Test
    fun midnightDoesNotResetTheLogicalDay() {
        val beforeMidnight = ZonedDateTime.of(2026, 7, 10, 23, 59, 59, 0, zone)
            .toInstant()
            .toEpochMilli()
        val earned = applied(engine.recordPushupRepetitions(
            engine.normalize(PersistedState(), beforeMidnight, zone),
            9,
            beforeMidnight,
            zone,
        ))
        val state = earned.copy(dailyUsage = earned.dailyUsage.copy(usedMs = MINUTE_MS))

        val after = engine.normalize(state, beforeMidnight + 2_000, zone)

        assertEquals("2026-07-10", after.dailyCounter.localDayKey)
        assertEquals(1, after.dailyCounter.earnedBudgetRounds)
        assertEquals(1, after.dailyCounter.spendableBudgetRounds)
        assertEquals(24, after.dailyCounter.exercisePointSixths)
        assertEquals(MINUTE_MS, after.dailyUsage.usedMs)
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
        assertEquals(2, afterSecond.usageReports.single().exerciseSets)
        assertEquals(
            listOf(ActivityEventType.EXERCISE_SET, ActivityEventType.EXERCISE_SET),
            afterSecond.activityEvents.map { it.type },
        )
        assertEquals(listOf(now, now + 1_000), afterSecond.activityEvents.map { it.startedAtWallMs })
        assertEquals(
            listOf(3 * MINUTE_MS, 3 * MINUTE_MS),
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
        assertEquals(2, week.single { it.localDayKey == "2026-07-10" }.exerciseSets)
    }

    @Test
    fun earlyMondayStillShowsThePreviousLogicalWeekUntilFourAm() {
        val earlyMonday = ZonedDateTime.of(2026, 7, 13, 0, 30, 0, 0, zone)
            .toInstant()
            .toEpochMilli()
        val state = engine.normalize(PersistedState(), earlyMonday, zone).copy(
            usageReports = listOf(DailyUsageReport("2026-07-12", exerciseSets = 2)),
        )

        val week = engine.snapshot(state, ready, earlyMonday, zoneId = zone).currentWeekUsageReports

        assertEquals("2026-07-06", week.first().localDayKey)
        assertEquals("2026-07-12", week.last().localDayKey)
        assertEquals(2, week.last().exerciseSets)
    }

    @Test
    fun normalizationArchivesLegacyCurrentDayPushupsBeforeDailyReset() {
        val nextDay = now + DAY_MS
        val legacy = initial().copy(
            dailyCounter = DailyCounter("2026-07-10", earnedBudgetRounds = 3),
            usageReports = emptyList(),
        )

        val normalized = engine.normalize(legacy, nextDay, zone)

        assertEquals(3, normalized.usageReports.single { it.localDayKey == "2026-07-10" }.exerciseSets)
        assertEquals(0, normalized.dailyCounter.earnedBudgetRounds)
        assertEquals("2026-07-11", normalized.dailyCounter.localDayKey)
    }

    @Test
    fun reportRetentionKeepsTodayPlusThirtyHistoricalDays() {
        var state = engine.normalize(PersistedState(), now - 31 * DAY_MS, zone)
        repeat(32) { offset ->
            val start = now - (31 - offset) * DAY_MS
            state = state.copy(
                activeUnlock = UnlockSession("active-$offset", UnlockSource.EMERGENCY, start, start + 2_000),
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
                DailyUsageReport("2026-07-10", protectedUsageMs = 12_000, exerciseSets = 2),
            ),
        )
        val cleared = engine.clearReportHistory(state)
        assertTrue(cleared.usageReports.isEmpty())
        assertEquals(12_000, cleared.dailyUsage.usedMs)
    }

    @Test
    fun clearingDailyReportsNeverDeletesMonthlyPushupHistory() {
        val cycles = listOf(
            MonthlyPushupCycle("2026-06-09", 70),
            MonthlyPushupCycle("2026-07-09", 80),
        )
        val state = initial().copy(monthlyPushupCycles = cycles)

        assertEquals(cycles, engine.clearReportHistory(state).monthlyPushupCycles)
    }

    @Test
    fun cancellingActiveUnlockPreservesUsageAndOtherState() {
        val base = initial()
        val unlock = UnlockSession("normal", UnlockSource.NORMAL, now, now + 30 * MINUTE_MS)
        val report = DailyUsageReport(
            "2026-07-10",
            protectedUsageMs = 12_000,
            exerciseSets = 2,
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
