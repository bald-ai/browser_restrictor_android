package com.michalkrsik.internetrestrictor.domain

import com.michalkrsik.internetrestrictor.data.AppStateSerializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ExerciseCorrectionTest {
    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val engine = RestrictionEngine()
    private fun initial() = engine.normalize(PersistedState(), now, zone)
    private fun record(state: PersistedState = initial(), value: Long = 1, id: String = "video", exercise: ExerciseId = ExerciseId.PUSH_UP) =
        (engine.recordVerifiedExercise(state, id, exercise, value, now, zone) as CommandResult.Applied).state
    private fun correct(state: PersistedState, value: Long, exercise: ExerciseId = ExerciseId.PUSH_UP, id: String = "video", time: Long = now, flag: Boolean = false) =
        engine.correctExercise(state, id, exercise, value, flag, time, zone)
    private fun applied(result: CommandResult) = (result as CommandResult.Applied).state

    @Test fun correctionsRejectUnsafeBalancesAndChangedPushupTotalsWithoutPublishingEdits() {
        val recorded = record(value = 5)
        val counter = recorded.dailyCounter
        val unsafe = listOf(
            recorded.copy(dailyCounter = counter.copy(earnedBudgetRounds = 0, spendableBudgetRounds = 0)),
            recorded.copy(dailyCounter = counter.copy(earnedBudgetRounds = 1, spendableBudgetRounds = 0)),
            recorded.copy(dailyCounter = counter.copy(verifiedPushups = 0)),
            recorded.copy(monthlyPushupCycles = emptyList()),
        )
        for (state in unsafe) {
            val result = correct(state, 0)
            assertTrue(result.toString(), result is CommandResult.Rejected)
            assertEquals(state.lastExercise, (result as CommandResult.Rejected).state.lastExercise)
            assertTrue(result.state.correctionHistory.isEmpty())
        }
        val capped = recorded.copy(dailyCounter = counter.copy(earnedBudgetRounds = MAX_SAFE_BUDGET_ROUNDS, spendableBudgetRounds = MAX_SAFE_BUDGET_ROUNDS))
        assertTrue(correct(capped, 10) is CommandResult.Rejected)
        val fullCycle = recorded.copy(monthlyPushupCycles = recorded.monthlyPushupCycles.map { it.copy(totalPushups = Long.MAX_VALUE) })
        assertTrue(correct(fullCycle, 6) is CommandResult.Rejected)
        val granted = recorded.copy(dailyCounter = counter.copy(grantedBudgetMinutes = 3))
        assertEquals(6L, applied(correct(granted, 10)).dailyCounter.grantedBudgetMinutes)
        val noCycle = record(value = 1, exercise = ExerciseId.PLANK).copy(monthlyPushupCycles = emptyList())
        assertEquals(5L, applied(correct(noCycle, 5)).monthlyPushupCycles.single().totalPushups)
    }

    @Test fun noPositiveVerifierReceiptCannotAuthorizeCorrection() {
        assertTrue(correct(initial(), 5) is CommandResult.Rejected)
        for (exercise in listOf(ExerciseId.PUSH_UP, ExerciseId.UNSUPPORTED)) {
            val result = engine.recordVerifiedExercise(initial(), "video", exercise, 0, now, zone)
            assertTrue(result is CommandResult.Rejected)
            assertTrue(correct((result as CommandResult.Rejected).state, 5) is CommandResult.Rejected)
        }
        val valid = record()
        val forgedZero = valid.copy(lastExercise = valid.lastExercise!!.copy(originalValue = 0))
        assertTrue(correct(forgedZero, 5) is CommandResult.Rejected)
        val unsupported = valid.copy(lastExercise = valid.lastExercise!!.copy(originalExercise = ExerciseId.UNSUPPORTED))
        assertTrue(correct(unsupported, 5) is CommandResult.Rejected)
    }

    @Test fun editsReplaceCreditAndMayReachZeroWithoutLosingOriginalEvidence() {
        val five = applied(correct(record(), 5))
        assertEquals(1L, five.dailyCounter.earnedBudgetRounds)
        assertEquals(5L, five.dailyCounter.verifiedPushups)
        assertEquals(1L, five.lastExercise!!.originalValue)
        val repeated = applied(correct(five, 5))
        assertEquals(five.dailyCounter, repeated.dailyCounter)
        assertEquals(1, repeated.activityEvents.size)
        val zero = applied(correct(repeated, 0))
        assertEquals(0L, zero.dailyCounter.earnedBudgetRounds)
        assertEquals(0L, zero.dailyCounter.verifiedPushups)
        assertEquals(0L, zero.monthlyPushupCycles.sumOf { it.totalPushups })
        assertTrue(zero.activityEvents.isEmpty())
        assertEquals(1L, applied(correct(zero, 5)).dailyCounter.earnedBudgetRounds)
    }

    @Test fun carryOverAndSprintRewardsArePreserved() {
        val previous = record(value = 4, id = "previous")
        val latest = record(previous, value = 1)
        val sprint = applied(engine.recordSprintRecording(latest, "sprint", 120, false, now, zone))
        val changed = applied(correct(sprint, 0))
        assertEquals(1L, changed.dailyCounter.earnedBudgetRounds)
        assertEquals(24L, changed.dailyCounter.exercisePointSixths)
        assertEquals(4L, changed.dailyCounter.verifiedPushups)
        assertEquals(1L, changed.usageReports.single().exerciseSets)
        assertTrue(correct(changed, 5, id = "previous") is CommandResult.Rejected)
    }

    @Test fun exerciseChangeRecalculatesPushupHistoryAndYoutubeEligibility() {
        val twenty = record(value = 20)
        assertEquals(4L, twenty.dailyCounter.earnedBudgetRounds)
        val plank = applied(correct(twenty, 12, ExerciseId.PLANK))
        assertEquals(0L, plank.dailyCounter.earnedBudgetRounds)
        assertEquals(24L, plank.dailyCounter.exercisePointSixths)
        assertEquals(0L, plank.dailyCounter.verifiedPushups)
        assertEquals(0L, plank.monthlyPushupCycles.sumOf { it.totalPushups })
        assertEquals(ExerciseId.PUSH_UP, plank.lastExercise!!.originalExercise)
    }

    @Test fun flagDoesNotChangeCreditAndResetsCloseCorrections() {
        val original = record(value = 5)
        val flagged = applied(correct(original, 999, flag = true))
        assertEquals(original.dailyCounter, flagged.dailyCounter)
        assertTrue(flagged.lastExercise!!.flagged)
        assertTrue(correct(original, 10, time = now + 8 * HOUR_MS) is CommandResult.Applied)
        assertTrue(correct(original, 10, time = now + DAY_MS) is CommandResult.Rejected)
        assertTrue(correct(original, -1) is CommandResult.Rejected)
        assertTrue(correct(original, 10_000) is CommandResult.Rejected)
        assertTrue(correct(original, 5, ExerciseId.UNSUPPORTED) is CommandResult.Rejected)
    }

    @Test fun correctionHistoryRecordsImmutableOriginalAndCorrectedFields() {
        val corrected = applied(correct(record(value = 6), 15, ExerciseId.PLANK, time = now + 1_000))
        val event = corrected.correctionHistory.single()
        assertTrue(event.id.isNotBlank())
        assertEquals("video", event.receiptId)
        assertEquals(now + 1_000, event.correctedAtWallMs)
        assertEquals(ExerciseId.PUSH_UP.wireId, event.originalType)
        assertEquals(6L, event.originalValue)
        assertEquals(ExerciseId.PLANK.wireId, event.correctedType)
        assertEquals(15L, event.correctedValue)
    }

    @Test fun sequentialEditsAppendOneEventEachAndKeepTheReviewerOriginal() {
        val recorded = record(value = 6)
        val first = applied(correct(recorded, 10, time = now + 1_000))
        val second = applied(correct(first, 4, time = now + 2_000))
        assertEquals(2, second.correctionHistory.size)
        val (earlier, later) = second.correctionHistory
        assertNotEquals(earlier.id, later.id)
        assertEquals(now + 1_000, earlier.correctedAtWallMs)
        assertEquals(10L, earlier.correctedValue)
        assertEquals(now + 2_000, later.correctedAtWallMs)
        assertEquals(4L, later.correctedValue)
        for (event in second.correctionHistory) {
            assertEquals(6L, event.originalValue)
            assertEquals(ExerciseId.PUSH_UP.wireId, event.originalType)
            assertEquals(ExerciseId.PUSH_UP.wireId, event.correctedType)
            assertEquals("video", event.receiptId)
        }
    }

    @Test fun unchangedSavesAddNoHistoryEvent() {
        val recorded = record(value = 5)
        assertTrue(applied(correct(recorded, 5)).correctionHistory.isEmpty())
        val changed = applied(correct(recorded, 8))
        assertEquals(1, changed.correctionHistory.size)
        assertEquals(1, applied(correct(changed, 8)).correctionHistory.size)
    }

    @Test fun typeOnlyEditAppendsHistoryEvent() {
        val corrected = applied(correct(record(value = 5), 5, ExerciseId.PLANK))
        val event = corrected.correctionHistory.single()
        assertEquals(ExerciseId.PUSH_UP.wireId, event.originalType)
        assertEquals(ExerciseId.PLANK.wireId, event.correctedType)
        assertEquals(5L, event.originalValue)
        assertEquals(5L, event.correctedValue)
    }

    @Test fun flagOnlyAddsNoHistoryEvent() {
        val flagged = applied(correct(record(value = 5), 999, flag = true))
        assertTrue(flagged.lastExercise!!.flagged)
        assertTrue(flagged.correctionHistory.isEmpty())
    }

    @Test fun historySurvivesNewRecordingResetsAndClearReportHistory() {
        val corrected = applied(correct(record(value = 6), 15, ExerciseId.PLANK))
        assertEquals(1, corrected.correctionHistory.size)
        val newer = applied(
            engine.recordVerifiedExercise(corrected, "video-2", ExerciseId.PUSH_UP, 5, now + MINUTE_MS, zone),
        )
        assertEquals("video-2", newer.lastExercise!!.id)
        assertEquals(corrected.correctionHistory, newer.correctionHistory)
        val afterEvening = engine.normalize(newer, now + 9 * HOUR_MS, zone)
        assertFalse(afterEvening.dailyCounter.legacyEveningResetApplied)
        assertEquals(corrected.correctionHistory, afterEvening.correctionHistory)
        val afterDaily = engine.normalize(newer, now + DAY_MS, zone)
        assertNotEquals(newer.dailyCounter.localDayKey, afterDaily.dailyCounter.localDayKey)
        assertEquals(corrected.correctionHistory, afterDaily.correctionHistory)
        assertEquals(corrected.correctionHistory, engine.clearReportHistory(afterDaily).correctionHistory)
    }

    @Test fun savedCorrectionsSurviveSerializationAndLegacyStateMigrates() = runTest {
        val state = applied(correct(record(value = 6), 15, ExerciseId.PLANK))
        assertEquals(1, state.correctionHistory.size)
        val bytes = ByteArrayOutputStream().also { AppStateSerializer.writeTo(state, it) }.toByteArray()
        assertEquals(state, AppStateSerializer.readFrom(ByteArrayInputStream(bytes)))
        // V13 is the same layout before the correction history tail.
        val recorded = record(value = 6)
        val v13 = ByteArrayOutputStream().also { AppStateSerializer.writeTo(recorded, it) }
            .toByteArray().dropLast(4).toByteArray()
        java.nio.ByteBuffer.wrap(v13).putInt(4, 13)
        val migrated13 = AppStateSerializer.readFrom(ByteArrayInputStream(v13))
        assertEquals(recorded.lastExercise, migrated13.lastExercise)
        assertTrue(migrated13.correctionHistory.isEmpty())
        // V12 is the same layout before the optional receipt tail.
        val empty = ByteArrayOutputStream().also { AppStateSerializer.writeTo(initial(), it) }
            .toByteArray().dropLast(5).toByteArray()
        java.nio.ByteBuffer.wrap(empty).putInt(4, 12)
        val migrated = AppStateSerializer.readFrom(ByteArrayInputStream(empty))
        assertNull(migrated.lastExercise)
        assertTrue(migrated.correctionHistory.isEmpty())
        assertEquals(initial().dailyCounter, migrated.dailyCounter)
    }
}
