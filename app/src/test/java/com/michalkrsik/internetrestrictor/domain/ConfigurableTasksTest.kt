package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ConfigurableTasksTest {
    private val engine = RestrictionEngine()
    private val zone = ZoneId.of("Europe/Prague")
    private val noon = at("2026-09-16T12:00:00+02:00[Europe/Prague]")
    private fun at(value: String) = ZonedDateTime.parse(value).toInstant().toEpochMilli()
    private fun applied(result: CommandResult): PersistedState {
        assertTrue("Expected applied, got $result", result is CommandResult.Applied)
        return (result as CommandResult.Applied).state
    }
    private fun initial(time: Long = noon, settings: ConfigurationSettings = ConfigurationSettings()) =
        applied(engine.applyConfiguration(PersistedState(), AppConfiguration(settings = settings), time, zone))
    private fun receipt(id: String, value: Long, units: Long = 5, end: Long = noon, rate: Long = 3, taskId: String = "custom") =
        TaskMeasurementReceipt(id, taskId, 0, TaskDefinition(taskId, "Task", "video", "repetitions", units, 1, "Count visible repetitions."),
            value, end - 10_000, end, minutesPerCredit = rate)
    private fun record(state: PersistedState, value: TaskMeasurementReceipt, now: Long = value.finishedAtWallMs) =
        applied(engine.applyTaskReceipt(state, value, now, zone))

    @Test fun identicalConfigurationStillFreezesAnUnmigratedBudgetOnce() {
        val base = initial()
        val legacy = engine.normalize(base.copy(dailyCounter = base.dailyCounter.copy(grantedBudgetMinutes = null, earnedBudgetRounds = 2, spendableBudgetRounds = 1)), noon, zone)
        val migrated = applied(engine.applyConfiguration(legacy, legacy.configuration, noon, zone))
        assertEquals(3L, migrated.dailyCounter.grantedBudgetMinutes)
        assertEquals(legacy.stateRevision + 1, migrated.stateRevision)
        assertEquals(migrated, applied(engine.applyConfiguration(migrated, migrated.configuration, noon, zone)))
    }

    @Test fun correctionEligibilityExpiresAtPinnedResetEvenWhenUserMovesCurrentResetLater() {
        val before = at("2026-09-17T03:59:00+02:00[Europe/Prague]")
        val after = at("2026-09-17T04:01:00+02:00[Europe/Prague]")
        var state = record(initial(before), receipt("pinned", 5, end = before))
        assertTrue("pinned" in engine.snapshot(state, RuntimeSignals(), before, zone).modifiableTaskReceiptIds)
        state = applied(engine.applyConfiguration(state, state.configuration.copy(revision = 1,
            settings = state.configuration.settings.copy(dayReset = "05:00")), before, zone))
        assertTrue(state.taskReceipts.single().rewardEligible)
        assertTrue(engine.snapshot(state, RuntimeSignals(), after, zone).modifiableTaskReceiptIds.isEmpty())
        assertTrue(engine.correctTaskReceipt(state, "pinned", 10, after, zone) is CommandResult.Rejected)
    }

    @Test fun correctedSecondsUseVideoDurationFromServerRatherThanAttemptCountdown() {
        val task = STARTER_TASKS.first { it.metric == "seconds" }
        val measurement = TaskMeasurementReceipt("video", task.id, 0, task, 1, noon - 33_500, noon,
            maximumCorrectableUnits = 29.9)
        val state = record(initial(), measurement)
        assertTrue(engine.correctTaskReceipt(state, "video", 30, noon, zone) is CommandResult.Rejected)
        assertEquals(29L, applied(engine.correctTaskReceipt(state, "video", 29, noon, zone)).taskReceipts.single().value)
    }

    @Test fun nextExpiryUsesOnlyDailyResetAndHandlesClockChanges() {
        assertEquals(at("2026-09-17T04:00:00+02:00[Europe/Prague]"), PeriodCalculator.nextDailyReset(noon, zone))
        val evening = at("2026-09-16T20:01:00+02:00[Europe/Prague]")
        assertEquals(at("2026-09-17T04:00:00+02:00[Europe/Prague]"), PeriodCalculator.nextDailyReset(evening, zone))
        val repeated = at("2026-10-25T02:45:00+02:00[Europe/Prague]")
        val settings = ConfigurationSettings(dayReset = "02:30")
        assertEquals(at("2026-10-26T02:30:00+01:00[Europe/Prague]"), PeriodCalculator.nextDailyReset(repeated, zone, settings))
    }

    @Test fun firstServerRevisionZeroMayDifferFromLocalStarterAndSubsequentMismatchIsRejected() {
        val server = AppConfiguration(tasks = STARTER_TASKS.map { it.copy(instructions = "Server instructions") })
        val state = applied(engine.applyConfiguration(PersistedState(), server, noon, zone))
        assertTrue(state.hasAppliedServerConfiguration)
        assertEquals(0L, engine.snapshot(state, RuntimeSignals(), noon, zone).appliedConfigRevision)
        assertTrue(engine.applyConfiguration(state, server.copy(settings = server.settings.copy(waitSeconds = 9)), noon, zone) is CommandResult.Rejected)
        assertEquals(state, applied(engine.applyConfiguration(state, server, noon, zone)))
    }

    @Test fun changedRewardRateDoesNotRepriceExistingMinutesAndOldAttemptsKeepTheirRate() {
        var state = record(initial(), receipt("first", 5))
        state = applied(engine.applyConfiguration(state, state.configuration.copy(revision = 1,
            settings = state.configuration.settings.copy(minutesPerCredit = 10)), noon, zone))
        assertEquals(3L, engine.budgetSnapshots(state).single().limitMinutes)
        state = record(state, receipt("old-recording", 5, rate = 3))
        assertEquals(6L, engine.budgetSnapshots(state).single().limitMinutes)
        state = record(state, receipt("new-recording", 5, rate = 10))
        assertEquals(16L, engine.budgetSnapshots(state).single().limitMinutes)
    }

    @Test fun legacyMigrationFreezesExistingBalanceBeforeNewRate() {
        val legacy = engine.normalize(PersistedState(), noon, zone).copy(dailyCounter = DailyCounter(
            localDayKey = "2026-09-16", earnedBudgetRounds = 4, spendableBudgetRounds = 2),
            dailyUsage = UsagePeriodState(BudgetPeriod.DAILY, "2026-09-16", MINUTE_MS))
        val state = applied(engine.applyConfiguration(legacy, AppConfiguration(revision = 1,
            settings = ConfigurationSettings(minutesPerCredit = 20)), noon, zone))
        assertEquals(5L * MINUTE_MS, engine.budgetSnapshots(state).single().remainingMs)
        assertEquals(4L, state.dailyCounter.earnedBudgetRounds)
    }

    @Test fun arbitraryTaskRatiosShareExactProgress() {
        var state = initial()
        repeat(3) { state = record(state, receipt("third-$it", 1, units = 3)) }
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(0.0, state.taskCreditProgress.approximate, 0.0)
        state = record(state, receipt("half", 1, units = 2, taskId = "other"))
        state = record(state, receipt("sixth", 1, units = 6))
        state = record(state, receipt("third", 1, units = 3))
        assertEquals(2L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(0.0, state.taskCreditProgress.approximate, 0.0)
    }

    @Test fun repeatedReceiptNeverPaysAgainIncludingAfterDailyReset() {
        val r = receipt("once", 5)
        val first = record(initial(), r)
        assertEquals(first, record(first, r))
        val tomorrow = noon + DAY_MS
        val next = record(first, r, tomorrow)
        assertEquals(0L, next.dailyCounter.earnedBudgetRounds)
        assertEquals(1, next.taskReceipts.size)
    }

    @Test fun lateSameDayVerificationKeepsCreditsMinutesAndCorrections() {
        val beforeReset = at("2026-09-16T19:59:00+02:00[Europe/Prague]")
        val afterReset = at("2026-09-16T20:01:00+02:00[Europe/Prague]")
        val state = record(initial(beforeReset), receipt("late", 8, end = beforeReset), afterReset)
        assertTrue(state.taskReceipts.single().rewardEligible)
        assertTrue(state.taskReceipts.single().minutesRewardEligible)
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(1L, state.dailyCounter.spendableBudgetRounds)
        assertEquals(3L, engine.budgetSnapshots(state).single().limitMinutes)
        assertEquals(0.6, state.taskCreditProgress.approximate, 0.0)
        assertEquals(setOf("late"), engine.snapshot(state, RuntimeSignals(), afterReset, zone).modifiableTaskReceiptIds)
    }

    @Test fun legacyExpiredMinutesStillPreserveRecordedPartialProgress() {
        val before = at("2026-09-16T19:59:00+02:00[Europe/Prague]")
        val after = at("2026-09-16T20:01:00+02:00[Europe/Prague]")
        val delayed = receipt("late-partial", 3, end = before).copy(minutesRewardEligible = false)
        var state = record(initial(before), delayed, after)
        assertEquals(0.6, state.taskCreditProgress.approximate, 0.0)
        assertEquals(0L, engine.budgetSnapshots(state).single().limitMinutes)
        assertEquals(state, record(state, delayed, after))
        state = record(state, receipt("new-partial", 2, end = after))
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(1L, state.dailyCounter.spendableBudgetRounds)
        assertEquals(3L, engine.budgetSnapshots(state).single().limitMinutes)
        assertEquals(0.0, state.taskCreditProgress.approximate, 0.0)
    }

    @Test fun fullDelayedCreditStillAwardsMinutesAcrossTwentyHundred() {
        val before = at("2026-09-16T19:59:00+02:00[Europe/Prague]")
        val after = at("2026-09-16T20:01:00+02:00[Europe/Prague]")
        val state = record(initial(before, ConfigurationSettings(youtubeCredits = 1)), receipt("late-full", 5, end = before), after)
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(1L, state.dailyCounter.spendableBudgetRounds)
        assertEquals(3L, engine.budgetSnapshots(state).single().limitMinutes)
        assertEquals(1L, engine.snapshot(state, RuntimeSignals(), after, zone).skipSnapshot.remaining)
        assertEquals(0.0, state.taskCreditProgress.approximate, 0.0)
    }

    @Test fun delayedOldActivityNeverRepricesOrRevokesAlreadyEarnedCurrentMinutes() {
        val before = at("2026-09-16T19:59:00+02:00[Europe/Prague]")
        val after = at("2026-09-16T20:01:00+02:00[Europe/Prague]")
        var state = record(initial(before), receipt("known-before", 3, end = before))
        state = record(state, receipt("new-after", 2, end = after, rate = 7))
        state = state.copy(dailyUsage = state.dailyUsage.copy(usedMs = MINUTE_MS))
        state = record(state, receipt("delayed-before", 3, end = before, rate = 15), after)
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(7L, engine.budgetSnapshots(state).single().limitMinutes)
        assertEquals(MINUTE_MS, state.dailyUsage.usedMs)
        assertEquals(0.6, state.taskCreditProgress.approximate, 0.0)
        assertEquals(7L, state.taskReceipts.first { it.measurement.id == "new-after" }.awardedMinutes)
    }

    @Test fun lateReceiptCrossingACreditAddsDailyAccessAndMinutes() {
        val before = at("2026-09-16T19:59:00+02:00[Europe/Prague]")
        val after = at("2026-09-16T20:01:00+02:00[Europe/Prague]")
        var state = record(initial(after), receipt("new-after", 4, end = after))
        state = record(state, receipt("old-late", 3, end = before), after)
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(1L, state.dailyCounter.spendableBudgetRounds)
        assertEquals(3L, engine.budgetSnapshots(state).single().limitMinutes)
        assertEquals(0.4, state.taskCreditProgress.approximate, 0.0)
    }

    @Test fun pastDailyWindowReceiptIsHistoryOnlyEvenIfServerVerifiedBeforeReset() {
        val before = at("2026-09-17T03:59:00+02:00[Europe/Prague]")
        val after = at("2026-09-17T04:01:00+02:00[Europe/Prague]")
        val state = record(initial(before), receipt("past-day", 8, end = before), after)
        assertFalse(state.taskReceipts.single().rewardEligible)
        assertFalse(state.taskReceipts.single().minutesRewardEligible)
        assertEquals(0L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(0L, engine.budgetSnapshots(state).single().limitMinutes)
        assertEquals(0.0, state.taskCreditProgress.approximate, 0.0)
    }

    @Test fun dailyResetEditAndUndoCannotMakeAnUnsubmittedReceiptEligibleAgain() {
        val after = noon + HOUR_MS + MINUTE_MS
        var state = initial()
        val late = receipt("unsubmitted", 8)
        state = applied(engine.applyConfiguration(state, state.configuration.copy(revision = 1,
            settings = state.configuration.settings.copy(dayReset = "13:00")), noon, zone))
        state = engine.normalize(state, after, zone)
        state = applied(engine.applyConfiguration(state, state.configuration.copy(revision = 2,
            settings = state.configuration.settings.copy(dayReset = "04:00")), after, zone))
        state = record(state, late, after)
        assertEquals(noon + HOUR_MS, state.lastDailyRewardResetWallMs)
        assertFalse(state.taskReceipts.single().rewardEligible)
        assertEquals(0L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(0.0, state.taskCreditProgress.approximate, 0.0)
    }

    @Test fun eveningPreservesExactProgressUntilDailyReset() {
        val before = at("2026-09-16T19:59:00+02:00[Europe/Prague]")
        val after = at("2026-09-16T20:01:00+02:00[Europe/Prague]")
        var state = record(initial(before), receipt("one-third", 1, units = 3, end = before))
        state = engine.normalize(state, after, zone)
        assertEquals(1.0 / 3.0, state.taskCreditProgress.approximate, 0.000001)
        state = record(state, receipt("two-thirds", 2, units = 3, end = after))
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(3L, engine.budgetSnapshots(state).single().limitMinutes)
        state = record(state, receipt("partial", 3, end = after))
        state = engine.normalize(state, noon + DAY_MS, zone)
        assertEquals(0L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(0L, state.dailyCounter.exercisePointSixths)
        assertEquals(0.0, state.taskCreditProgress.approximate, 0.0)
    }

    @Test fun resetTimeEditAndUndoCannotRestoreExpiredMinutesOrProgress() {
        var state = record(initial(), receipt("earned", 8))
        state = engine.normalize(state, noon + DAY_MS, zone)
        assertEquals(0L, state.dailyCounter.earnedBudgetRounds)
        val changed = state.configuration.copy(revision = 1, settings = state.configuration.settings.copy(dayReset = "13:00"))
        state = applied(engine.applyConfiguration(state, changed, noon + DAY_MS, zone))
        state = applied(engine.applyConfiguration(state, changed.copy(revision = 2, settings = ConfigurationSettings()), noon + DAY_MS, zone))
        assertEquals(0L, engine.budgetSnapshots(state).single().limitMinutes)
        assertEquals(0.0, state.taskCreditProgress.approximate, 0.0)
        assertEquals(0L, state.dailyCounter.earnedBudgetRounds)
    }

    @Test fun resetEditPreservesAlreadyEarnedTimeUntilNextActualBoundary() {
        var state = record(initial(), receipt("earned", 5))
        state = applied(engine.applyConfiguration(state, state.configuration.copy(revision = 1,
            settings = ConfigurationSettings(dayReset = "13:00")), noon, zone))
        state = engine.normalize(state, noon + MINUTE_MS, zone)
        assertEquals(3L, engine.budgetSnapshots(state).single().limitMinutes)
        state = engine.normalize(state, noon + HOUR_MS, zone)
        assertEquals(0L, engine.budgetSnapshots(state).single().limitMinutes)
    }

    @Test fun correctionRecomputesLaterSharedProgressAndNeverChangesConsumedTime() {
        var state = record(initial(), receipt("first", 3))
        state = record(state, receipt("second", 2))
        state = state.copy(dailyUsage = state.dailyUsage.copy(usedMs = MINUTE_MS))
        state = applied(engine.correctTaskReceipt(state, "first", 1, noon, zone))
        assertEquals(0L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(0L, engine.budgetSnapshots(state).single().remainingMs)
        assertEquals(MINUTE_MS, state.dailyUsage.usedMs)
        assertEquals(0.6, state.taskCreditProgress.approximate, 0.000001)
        state = applied(engine.correctTaskReceipt(state, "first", 3, noon, zone))
        assertEquals(2L * MINUTE_MS, engine.budgetSnapshots(state).single().remainingMs)
        assertEquals(2, state.correctionHistory.size)
    }

    @Test fun correctionRequiresOriginalMinimumEvidenceAndCannotOutliveReset() {
        var state = record(initial(), receipt("zero", 0).copy(correctionEligible = false))
        assertTrue(engine.correctTaskReceipt(state, "zero", 5, noon, zone) is CommandResult.Rejected)
        state = record(state, receipt("positive", 1))
        assertTrue(engine.correctTaskReceipt(state, "positive", 5, noon + DAY_MS, zone) is CommandResult.Rejected)
    }

    @Test fun serverApprovedGpsFallbackAddsOnlyOneCreditOnce() {
        val task = STARTER_TASKS.first { it.id == "walk" }
        val receipt = TaskMeasurementReceipt("gps", "walk", 0, task, 0, noon - MINUTE_MS, noon,
            correctionEligible = false, fallbackEligible = true)
        var state = record(initial(), receipt)
        assertTrue(engine.correctTaskReceipt(state, "gps", 1000, noon, zone) is CommandResult.Rejected)
        state = record(state, receipt.copy(manualCompletion = true, correctedUnits = 1000))
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(state, record(state, receipt.copy(manualCompletion = true, correctedUnits = 1000)))
    }

    @Test fun customWaitYouTubeThresholdAndEmergencyDurationReachRuntime() {
        val settings = ConfigurationSettings(waitSeconds = 0, youtubeCredits = 1, emergencyMinutes = 7)
        var state = record(initial(settings = settings), receipt("credit", 5))
        val runtime = RuntimeSignals(activeTarget = ProtectedTarget.BROWSER_POOL, foregroundClass = ForegroundClass.BROWSER,
            storageReady = true, accessibilityEnabled = true, serviceConnected = true, gateSessionId = "gate", gateFocused = true)
        state = applied(engine.startWait(state, runtime, state.stateRevision, 3, noon, zone))
        assertNotNull(state.activeUnlock)
        assertNull(state.pendingWait)
        assertEquals(GateMode.YOUTUBE_ACCESS_GRANTED, engine.underlyingMode(state, runtime.copy(activeTarget = ProtectedTarget.YOUTUBE), noon))
        assertEquals(7L * MINUTE_MS, engine.snapshot(state, runtime, noon, zone).skipSnapshot.grantDurationMs)
    }

    @Test fun nonDefaultResetTimesSplitCorrectlyAcrossMidnightAndDst() {
        val settings = ConfigurationSettings(dayReset = "08:30")
        val start = at("2026-09-16T23:00:00+02:00[Europe/Prague]")
        val end = at("2026-09-17T09:00:00+02:00[Europe/Prague]")
        val intervals = PeriodCalculator.splitAtDailyResets(start, end, zone, settings)
        assertEquals(2, intervals.size)
        assertEquals(end - start, intervals.sumOf { it.durationMs })
        val beforeDst = at("2026-10-24T12:00:00+02:00[Europe/Prague]")
        val afterDst = at("2026-10-25T12:00:00+01:00[Europe/Prague]")
        assertEquals(25L * HOUR_MS, afterDst - beforeDst)
        assertEquals(afterDst - beforeDst, PeriodCalculator.splitAtDailyResets(beforeDst, afterDst, zone, settings).sumOf { it.durationMs })
    }

    @Test fun tenthsOfSecondsAndMetersContributeWithoutRoundingEachRecordingDown() {
        val task = TaskDefinition("hold", "Hold", "video", "seconds", 1, 1, "Measure held seconds")
        var state = initial()
        val first = TaskMeasurementReceipt("first", task.id, 0, task, 0, noon - 10000, noon,
            measuredUnitsText = "0.6", correctionEligible = false)
        state = record(state, first)
        assertEquals(0.6, state.taskCreditProgress.approximate, 0.000001)
        state = record(state, first.copy(id = "second", attemptId = "second", measuredUnitsText = "0.4"))
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        val walk = STARTER_TASKS.first { it.id == "walk" }
        state = record(state, TaskMeasurementReceipt("walk-1", walk.id, 0, walk, 99, noon - MINUTE_MS, noon, measuredUnitsText = "99.9"))
        state = record(state, TaskMeasurementReceipt("walk-2", walk.id, 0, walk, 0, noon - MINUTE_MS, noon, measuredUnitsText = "0.1", correctionEligible = false))
        assertEquals(2L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(0.0, state.taskCreditProgress.approximate, 0.0)
    }

    @Test fun fractionalCorrectionFromServerDoesNotDiscardMeasuredTenths() {
        val task = TaskDefinition("hold", "Hold", "video", "seconds", 2, 1, "Measure seconds")
        val receipt = TaskMeasurementReceipt("held", task.id, 0, task, 1, noon - 10000, noon, measuredUnitsText = "1.5")
        var state = record(initial(), receipt)
        assertEquals(0.75, state.taskCreditProgress.approximate, 0.0)
        state = record(state, receipt.copy(correctedUnits = 2, correctedUnitsText = "2.5"))
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(0.25, state.taskCreditProgress.approximate, 0.0)
        assertEquals("2.5", state.taskReceipts.single().valueText)
        assertEquals(state, record(state, receipt.copy(correctedUnits = 2, correctedUnitsText = "2.5")))
    }

    @Test fun durationCorrectionsCannotExceedActualRecordingLength() {
        val task = TaskDefinition("hold", "Hold", "video", "seconds", 5, 1, "Measure seconds")
        val receipt = TaskMeasurementReceipt("held", task.id, 0, task, 1, noon - 10_000, noon)
        val state = record(initial(), receipt)
        assertTrue(engine.correctTaskReceipt(state, receipt.id, 15, noon, zone) is CommandResult.Rejected)
        assertTrue(engine.correctTaskReceipt(state, receipt.id, 10, noon, zone) is CommandResult.Applied)
    }

    @Test fun changingResetThenUndoingAfterItFiresCannotMixExpiredReceiptsIntoNewLedger() {
        val before = at("2026-09-16T18:00:00+02:00[Europe/Prague]")
        val after = at("2026-09-16T19:15:00+02:00[Europe/Prague]")
        var state = record(initial(before), receipt("old-credit", 5, end = before))
        state = applied(engine.applyConfiguration(state, state.configuration.copy(revision = 1,
            settings = ConfigurationSettings(dayReset = "19:00")), before, zone))
        state = engine.normalize(state, after, zone)
        assertEquals(0L, engine.budgetSnapshots(state).single().limitMinutes)
        state = record(state, receipt("new-credit", 5, end = after))
        assertEquals(3L, engine.budgetSnapshots(state).single().limitMinutes)
        state = applied(engine.applyConfiguration(state, state.configuration.copy(revision = 2,
            settings = ConfigurationSettings()), after, zone))
        state = record(state, receipt("after-undo", 5, end = after))
        assertEquals(6L, engine.budgetSnapshots(state).single().limitMinutes)
        assertEquals(2L, state.dailyCounter.earnedBudgetRounds)
        assertTrue(engine.correctTaskReceipt(state, "old-credit", 10, after, zone) is CommandResult.Rejected)
        assertEquals(2, state.taskReceipts.map { it.budgetKey }.distinct().size)
    }

    @Test fun reusedReceiptOrAttemptIdCannotReplaceOriginalMeasurement() {
        val first = receipt("receipt", 5)
        val state = record(initial(), first)
        assertTrue(engine.applyTaskReceipt(state, first.copy(value = 10, measuredUnitsText = "10"), noon, zone) is CommandResult.Rejected)
        assertTrue(engine.applyTaskReceipt(state, first.copy(id = "different-receipt"), noon, zone) is CommandResult.Rejected)
    }

    @Test fun repeatedDstHourDoesNotMoveTheDayBackwardOrResetTwice() {
        val settings = ConfigurationSettings(dayReset = "02:30")
        val firstBefore = at("2026-10-25T02:15:00+02:00[Europe/Prague]")
        val firstAfter = at("2026-10-25T02:45:00+02:00[Europe/Prague]")
        val secondBefore = at("2026-10-25T02:15:00+01:00[Europe/Prague]")
        val secondAfter = at("2026-10-25T02:45:00+01:00[Europe/Prague]")
        assertEquals("2026-10-24", PeriodCalculator.keysAt(firstBefore, zone, settings).daily)
        listOf(firstAfter, secondBefore, secondAfter).forEach {
            assertEquals("2026-10-25", PeriodCalculator.keysAt(it, zone, settings).daily)
            assertEquals(at("2026-10-26T02:30:00+01:00[Europe/Prague]"), PeriodCalculator.nextDailyReset(it, zone, settings))
        }
        var state = initial(firstAfter, settings)
        state = record(state, receipt("dst-credit", 5, end = firstAfter).copy(dayReset = "02:30"))
        state = engine.normalize(state, secondBefore, zone)
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
        state = engine.normalize(state, secondAfter, zone)
        assertEquals(1L, state.dailyCounter.earnedBudgetRounds)
    }

    @Test fun missingDstResetTimeUsesTheFirstValidInstantAfterTheGap() {
        val settings = ConfigurationSettings(dayReset = "02:30")
        val before = at("2026-03-29T01:30:00+01:00[Europe/Prague]")
        val reset = at("2026-03-29T03:00:00+02:00[Europe/Prague]")
        val after = at("2026-03-29T03:15:00+02:00[Europe/Prague]")
        assertEquals(reset, PeriodCalculator.nextDailyReset(before, zone, settings))
        assertEquals("2026-03-29", PeriodCalculator.keysAt(after, zone, settings).daily)
        assertEquals(reset, PeriodCalculator.dailyResetStart(after, zone, settings))
        val segments = PeriodCalculator.splitAtDailyResets(before, after, zone, settings)
        assertEquals(2, segments.size)
        assertEquals(after - before, segments.sumOf { it.durationMs })
    }
}
