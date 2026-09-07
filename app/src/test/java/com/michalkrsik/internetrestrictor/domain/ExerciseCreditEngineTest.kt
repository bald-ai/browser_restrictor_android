package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseCreditEngineTest {
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

    private fun initial(): PersistedState = engine.normalize(PersistedState(), now, zone)

    private fun applied(result: CommandResult): PersistedState = when (result) {
        is CommandResult.Applied -> result.state
        is CommandResult.NormalCompletionWon -> result.state
        is CommandResult.Rejected -> error("Expected applied, got ${result.reason}")
    }

    @Test
    fun plankAndMachineContributeToSharedProgress() {
        val plankPartial = applied(engine.recordVerifiedExercise(initial(), ExerciseId.PLANK, 14, now, zone))
        assertEquals(0, plankPartial.dailyCounter.earnedBudgetRounds)
        assertEquals(28, plankPartial.dailyCounter.exercisePointSixths)
        assertEquals(0, plankPartial.dailyCounter.verifiedPushups)
        assertEquals(0, plankPartial.monthlyPushupCycles.single().totalPushups)

        val plankSet = applied(engine.recordVerifiedExercise(plankPartial, ExerciseId.PLANK, 1, now + 1, zone))
        assertEquals(1, plankSet.dailyCounter.earnedBudgetRounds)
        assertEquals(0, plankSet.dailyCounter.exercisePointSixths)
        assertEquals(3, engine.budgetSnapshots(plankSet).single().limitMinutes)
        assertEquals(0, plankSet.dailyCounter.verifiedPushups)
        assertEquals(0, plankSet.monthlyPushupCycles.single().totalPushups)

        val machine = applied(engine.recordVerifiedExercise(plankSet, ExerciseId.OUTDOOR_LAT_PULLDOWN, 10, now + 2, zone))
        assertEquals(2, machine.dailyCounter.earnedBudgetRounds)
        assertEquals(0, machine.dailyCounter.exercisePointSixths)
        assertEquals(0, machine.dailyCounter.verifiedPushups)
        assertEquals(0, machine.monthlyPushupCycles.single().totalPushups)
    }

    @Test
    fun oneRecordingCanEarnMultipleSetsAndKeepTheCorrectRemainder() {
        val plank = applied(engine.recordVerifiedExercise(initial(), ExerciseId.PLANK, 32, now, zone))
        assertEquals(2, plank.dailyCounter.earnedBudgetRounds)
        assertEquals(4, plank.dailyCounter.exercisePointSixths)
        assertEquals(6, engine.budgetSnapshots(plank).single().limitMinutes)
        assertEquals(6 * MINUTE_MS, plank.activityEvents.single().valueMs)

        val machine = applied(
            engine.recordVerifiedExercise(initial(), ExerciseId.OUTDOOR_LAT_PULLDOWN, 25, now, zone),
        )
        assertEquals(2, machine.dailyCounter.earnedBudgetRounds)
        assertEquals(15, machine.dailyCounter.exercisePointSixths)
    }

    @Test
    fun mixedExercisesUnlockYoutubeAtFourSetsWithoutFakePushups() {
        val pushups = applied(engine.recordVerifiedExercise(initial(), ExerciseId.PUSH_UP, 5, now, zone))
        val plank = applied(engine.recordVerifiedExercise(pushups, ExerciseId.PLANK, 15, now + 1, zone))
        val machine = applied(
            engine.recordVerifiedExercise(plank, ExerciseId.OUTDOOR_LAT_PULLDOWN, 10, now + 2, zone),
        )
        assertEquals(GateMode.YOUTUBE_LOCKED, engine.underlyingMode(machine, youtubeReady, now + 2))
        assertEquals(5, machine.dailyCounter.verifiedPushups)

        val unlocked = applied(engine.recordVerifiedExercise(machine, ExerciseId.PLANK, 15, now + 3, zone))
        assertEquals(4, unlocked.dailyCounter.earnedBudgetRounds)
        assertEquals(5, unlocked.dailyCounter.verifiedPushups)
        assertEquals(5, unlocked.monthlyPushupCycles.single().totalPushups)
        assertEquals(GateMode.YOUTUBE_ACCESS_GRANTED, engine.underlyingMode(unlocked, youtubeReady, now + 3))
    }

    @Test
    fun eveningResetKeepsSetsAndSharedProgressWhileRefreshingSpendableBudget() {
        val beforeReset = ZonedDateTime.of(2026, 7, 10, 19, 59, 59, 0, zone)
            .toInstant()
            .toEpochMilli()
        val earned = applied(engine.recordVerifiedExercise(
            engine.normalize(PersistedState(), beforeReset, zone),
            ExerciseId.PLANK,
            20,
            beforeReset,
            zone,
        ))
        val withPushupSurplus = applied(
            engine.recordVerifiedExercise(earned, ExerciseId.PUSH_UP, 4, beforeReset + 1, zone),
        )
        val after = engine.normalize(withPushupSurplus, beforeReset + 2_000, zone)

        assertEquals(2, after.dailyCounter.earnedBudgetRounds)
        assertEquals(0, after.dailyCounter.spendableBudgetRounds)
        assertEquals(4, after.dailyCounter.exercisePointSixths)
        assertEquals(4, after.dailyCounter.verifiedPushups)
        assertTrue(after.dailyCounter.eveningResetApplied)
        assertEquals(1, engine.snapshot(after, ready, beforeReset + 2_000).skipSnapshot.remaining)
        assertEquals(GateMode.OUT_OF_TIME, engine.underlyingMode(after, ready, beforeReset + 2_000))

        val eveningSet = applied(
            engine.recordVerifiedExercise(after, ExerciseId.OUTDOOR_LAT_PULLDOWN, 10, beforeReset + 3_000, zone),
        )
        assertEquals(3, eveningSet.dailyCounter.earnedBudgetRounds)
        assertEquals(1, eveningSet.dailyCounter.spendableBudgetRounds)
        assertEquals(3, engine.budgetSnapshots(eveningSet).single().limitMinutes)
        assertEquals(4, eveningSet.dailyCounter.verifiedPushups)
    }

    @Test
    fun fourAmResetClearsSetsAndAllExerciseSurplus() {
        val beforeReset = ZonedDateTime.of(2026, 7, 11, 3, 59, 58, 0, zone).toInstant().toEpochMilli()
        val base = engine.normalize(PersistedState(), beforeReset, zone).copy(
            dailyCounter = DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 3,
                pushupRepetitionRemainder = 4,
                spendableBudgetRounds = 0,
                eveningResetApplied = true,
                plankSecondRemainder = 7,
                machineRepetitionRemainder = 9,
                verifiedPushups = 19,
                creditedExerciseVerificationIds = listOf("before-reset"),
            ),
        )
        val after = engine.normalize(base, beforeReset + 3_000, zone)
        assertEquals(0, after.dailyCounter.earnedBudgetRounds)
        assertEquals(0, after.dailyCounter.exercisePointSixths)
        assertEquals(0, after.dailyCounter.exercisePointSixths)
        assertEquals(0, after.dailyCounter.exercisePointSixths)
        assertEquals(0, after.dailyCounter.verifiedPushups)
        assertEquals(emptyList<String>(), after.dailyCounter.creditedExerciseVerificationIds)
        assertFalse(after.dailyCounter.eveningResetApplied)
    }

    @Test
    fun unsupportedAndZeroMeasurementsAreRejectedWithoutCredit() {
        val start = initial()
        val unsupported = engine.recordVerifiedExercise(start, ExerciseId.UNSUPPORTED, 10, now, zone)
        assertTrue(unsupported is CommandResult.Rejected)
        assertEquals(0, (unsupported as CommandResult.Rejected).state.dailyCounter.earnedBudgetRounds)

        val sitUp = engine.recordVerifiedExercise(start, ExerciseId.SIT_UP, 12, now, zone)
        assertTrue(sitUp is CommandResult.Rejected)

        val zero = engine.recordVerifiedExercise(start, ExerciseId.PLANK, 0, now, zone)
        assertTrue(zero is CommandResult.Rejected)
        assertEquals(start.dailyCounter, (zero as CommandResult.Rejected).state.dailyCounter)
    }

    @Test
    fun applyingOneVerifiedResultDoesNotDoubleCountRemainderOrForeignLedgers() {
        val first = applied(engine.recordVerifiedExercise(initial(), ExerciseId.PLANK, 20, now, zone))
        assertEquals(1, first.dailyCounter.earnedBudgetRounds)
        assertEquals(10, first.dailyCounter.exercisePointSixths)
        assertEquals(1, first.activityEvents.size)
        assertEquals(0, first.dailyCounter.verifiedPushups)

        val unchanged = applied(engine.recordVerifiedExercise(first, ExerciseId.PUSH_UP, 3, now + 1, zone))
        assertEquals(1, unchanged.dailyCounter.earnedBudgetRounds)
        assertEquals(28, unchanged.dailyCounter.exercisePointSixths)
        assertEquals(3, unchanged.dailyCounter.verifiedPushups)
        assertEquals(3, unchanged.monthlyPushupCycles.single().totalPushups)
        assertEquals(1, unchanged.activityEvents.size)
    }

    @Test
    fun replayingTheSameVerificationIdCannotEarnCreditTwice() {
        val verificationId = "recording-123"
        val first = applied(engine.recordVerifiedExercise(
            input = initial(),
            verificationId = verificationId,
            exerciseId = ExerciseId.PLANK,
            value = 20,
            nowWallMs = now,
            zoneId = zone,
        ))

        val replay = engine.recordVerifiedExercise(
            input = first,
            verificationId = verificationId,
            exerciseId = ExerciseId.PLANK,
            value = 20,
            nowWallMs = now + 1,
            zoneId = zone,
        )

        assertTrue(replay is CommandResult.Rejected)
        val unchanged = (replay as CommandResult.Rejected).state
        assertEquals(first.dailyCounter, unchanged.dailyCounter)
        assertEquals(first.activityEvents, unchanged.activityEvents)
        assertEquals(first.monthlyPushupCycles, unchanged.monthlyPushupCycles)
        assertEquals(listOf(verificationId), unchanged.dailyCounter.creditedExerciseVerificationIds)
    }

    @Test
    fun firstCompletedSetFromAnyExerciseEnablesEmergencyUnlock() {
        val before = engine.snapshot(initial(), ready, now)
        assertFalse(before.skipSnapshot.eligible)

        val plank = applied(engine.recordVerifiedExercise(initial(), ExerciseId.PLANK, 15, now, zone))
        val after = engine.snapshot(plank, ready, now)
        assertTrue(after.skipSnapshot.eligible)
        assertEquals(1, after.skipSnapshot.remaining)
        assertEquals(0, plank.dailyCounter.verifiedPushups)
    }

    @Test
    fun accumulateExerciseUnitsMatchesSetBoundaries() {
        assertEquals(ExerciseAccumulation(0, 4), accumulateExerciseUnits(0, 4, PUSHUPS_PER_SET))
        assertEquals(ExerciseAccumulation(1, 0), accumulateExerciseUnits(4, 1, PUSHUPS_PER_SET))
        assertEquals(ExerciseAccumulation(2, 2), accumulateExerciseUnits(0, 32, PLANK_SECONDS_PER_SET))
        assertEquals(ExerciseAccumulation(1, 0), accumulateExerciseUnits(0, 10, MACHINE_REPS_PER_SET))
    }
    @Test
    fun threePushupsAndFourMachineRepsEarnOneBoost() {
        val pushups = applied(engine.recordVerifiedExercise(initial(), ExerciseId.PUSH_UP, 3, now, zone))
        assertEquals(0, pushups.dailyCounter.earnedBudgetRounds)
        assertEquals(18, pushups.dailyCounter.exercisePointSixths)
        val combined = applied(engine.recordVerifiedExercise(pushups, ExerciseId.OUTDOOR_LAT_PULLDOWN, 4, now + 1, zone))
        assertEquals(1, combined.dailyCounter.earnedBudgetRounds)
        assertEquals(0, combined.dailyCounter.exercisePointSixths)
        assertEquals(3, engine.budgetSnapshots(combined).single().limitMinutes)
        assertEquals(3, combined.dailyCounter.verifiedPushups)
        assertEquals(3, combined.monthlyPushupCycles.single().totalPushups)
    }

    @Test
    fun fractionsFromAllExercisesAccumulateExactlyWithoutEarlyReward() {
        var state = applied(engine.recordVerifiedExercise(initial(), ExerciseId.PUSH_UP, 4, now, zone))
        state = applied(engine.recordVerifiedExercise(state, ExerciseId.OUTDOOR_LAT_PULLDOWN, 1, now + 1, zone))
        state = applied(engine.recordVerifiedExercise(state, ExerciseId.PLANK, 1, now + 2, zone))
        assertEquals(29, state.dailyCounter.exercisePointSixths)
        assertEquals(0, state.dailyCounter.earnedBudgetRounds)
        state = applied(engine.recordVerifiedExercise(state, ExerciseId.PLANK, 1, now + 3, zone))
        assertEquals(1, state.dailyCounter.exercisePointSixths)
        assertEquals(1, state.dailyCounter.earnedBudgetRounds)
    }

    @Test
    fun legacySurplusIsCombinedOnceWithoutChangingRecordedPushupsOrUsage() {
        val old = initial().copy(
            dailyCounter = initial().dailyCounter.copy(
                pushupRepetitionRemainder = 3, machineRepetitionRemainder = 4,
                plankSecondRemainder = 1, verifiedPushups = 3,
            ),
            dailyUsage = initial().dailyUsage.copy(usedMs = 1234),
        )
        val migrated = engine.normalize(old, now, zone)
        assertEquals(1, migrated.dailyCounter.earnedBudgetRounds)
        assertEquals(1, migrated.dailyCounter.spendableBudgetRounds)
        assertEquals(2, migrated.dailyCounter.exercisePointSixths)
        assertEquals(0, migrated.dailyCounter.pushupRepetitionRemainder)
        assertEquals(0, migrated.dailyCounter.machineRepetitionRemainder)
        assertEquals(0, migrated.dailyCounter.plankSecondRemainder)
        assertEquals(3, migrated.dailyCounter.verifiedPushups)
        assertEquals(old.monthlyPushupCycles, migrated.monthlyPushupCycles)
        assertEquals(old.dailyUsage, migrated.dailyUsage)
        assertEquals(1, migrated.usageReports.single().exerciseSets)
        assertEquals(migrated, engine.normalize(migrated, now + 1, zone))
    }

    @Test
    fun oversizedMeasurementIsRejectedWithoutChangingProgress() {
        val before = initial()
        val result = engine.recordVerifiedExercise(before, ExerciseId.PUSH_UP, Long.MAX_VALUE, now, zone)
        assertTrue(result is CommandResult.Rejected)
        assertEquals(before, (result as CommandResult.Rejected).state)
    }

}
