package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SprintRewardEngineTest {

    private val zone = ZoneId.of("UTC")
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
    private val youtubeReady = ready.copy(
        foregroundClass = ForegroundClass.OTHER,
        activeTarget = ProtectedTarget.YOUTUBE,
    )

    private fun wallMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun sprintRecordingEarnsSetsAndSpendableBudget() {
        val now = wallMs(2026, 7, 10, 10, 0)
        val initial = engine.normalize(PersistedState(), now, zone)

        // Record a 130m qualifying sprint (120m earns 1 set, 10m remainder)
        val result = engine.recordSprintRecording(
            input = initial,
            recordingId = "sprint-1",
            qualifyingMeters = 130L,
            manualCompletionAward = false,
            nowWallMs = now,
            zoneId = zone,
        )

        assertTrue(result is CommandResult.Applied)
        val updated = (result as CommandResult.Applied).state

        assertEquals(1L, updated.dailyCounter.earnedBudgetRounds)
        assertEquals(1L, updated.dailyCounter.spendableBudgetRounds)
        assertEquals(10L, updated.dailyCounter.sprintMetersRemainder)
        assertEquals(listOf("sprint-1"), updated.dailyCounter.creditedExerciseVerificationIds)

        // Snapshot reflects budget and carryover
        val snapshot = engine.snapshot(updated, RuntimeSignals(), now, zone)
        assertEquals(1L, snapshot.earnedBudgetRoundsToday)
        assertEquals(10L, snapshot.sprintMetersRemainder)
        val budget = snapshot.budgetSnapshots.first()
        assertEquals(3L, budget.limitMinutes) // 1 set * 3 min
    }

    @Test
    fun sprintRemainderSurvives2000Reset() {
        val morning = wallMs(2026, 7, 10, 10, 0)
        val initial = engine.normalize(PersistedState(), morning, zone)

        // Sprint 50m (less than 120m -> 0 sets, 50m remainder)
        val result = engine.recordSprintRecording(
            input = initial,
            recordingId = "sprint-short",
            qualifyingMeters = 50L,
            manualCompletionAward = false,
            nowWallMs = morning,
            zoneId = zone,
        )
        val stateAfterMorning = (result as CommandResult.Applied).state
        assertEquals(0L, stateAfterMorning.dailyCounter.earnedBudgetRounds)
        assertEquals(50L, stateAfterMorning.dailyCounter.sprintMetersRemainder)

        // Cross 20:00 evening budget reset
        val evening = wallMs(2026, 7, 10, 20, 15)
        val normalizedEvening = engine.normalize(stateAfterMorning, evening, zone)

        assertEquals(true, normalizedEvening.dailyCounter.eveningResetApplied)
        assertEquals(0L, normalizedEvening.dailyCounter.spendableBudgetRounds)
        // Sprint remainder survives 20:00!
        assertEquals(50L, normalizedEvening.dailyCounter.sprintMetersRemainder)
    }

    @Test
    fun sprintRemainderResetsAt0400AlongsideUnfinishedExerciseProgress() {
        val day1 = wallMs(2026, 7, 10, 15, 0)
        val initial = engine.normalize(PersistedState(), day1, zone)

        val result = engine.recordSprintRecording(
            input = initial,
            recordingId = "sprint-day1",
            qualifyingMeters = 75L,
            manualCompletionAward = false,
            nowWallMs = day1,
            zoneId = zone,
        )
        val stateDay1 = (result as CommandResult.Applied).state
        assertEquals(75L, stateDay1.dailyCounter.sprintMetersRemainder)

        // Cross 04:00 next day
        val day2 = wallMs(2026, 7, 11, 4, 30)
        val normalizedDay2 = engine.normalize(stateDay1, day2, zone)

        // Resets alongside unfinished exercise progress!
        assertEquals(0L, normalizedDay2.dailyCounter.sprintMetersRemainder)
        assertEquals(0L, normalizedDay2.dailyCounter.exercisePointSixths)
        assertEquals(0L, normalizedDay2.dailyCounter.earnedBudgetRounds)
    }

    @Test
    fun sprintSetsContributeToYouTubeUnlock() {
        val now = wallMs(2026, 7, 10, 11, 0)
        var state = engine.normalize(PersistedState(), now, zone)

        // YouTube requires 4 sets
        for (i in 1..4) {
            val res = engine.recordSprintRecording(
                input = state,
                recordingId = "sprint-yt-$i",
                qualifyingMeters = 120L,
                manualCompletionAward = false,
                nowWallMs = now + i * 1000L,
                zoneId = zone,
            )
            state = (res as CommandResult.Applied).state
        }

        assertEquals(4L, state.dailyCounter.earnedBudgetRounds)
        val snapshot = engine.snapshot(state, youtubeReady, now + 5000L, zone)
        // With 4 sets earned, YouTube is unlocked!
        assertEquals(GateMode.YOUTUBE_ACCESS_GRANTED, snapshot.canonicalMode)
    }

    @Test
    fun firstSprintSetEnablesEmergencyUnlock() {
        val now = wallMs(2026, 7, 10, 11, 0)
        val initial = engine.normalize(PersistedState(), now, zone)

        val beforeSnapshot = engine.snapshot(initial, ready, now, zone)
        assertFalse("Emergency skip should not be eligible before any sets", beforeSnapshot.skipSnapshot.eligible)

        val result = engine.recordSprintRecording(
            input = initial,
            recordingId = "sprint-first",
            qualifyingMeters = 120L,
            manualCompletionAward = false,
            nowWallMs = now,
            zoneId = zone,
        )
        val updated = (result as CommandResult.Applied).state

        val afterSnapshot = engine.snapshot(updated, ready, now + 1000L, zone)
        assertTrue("First completed sprint set should enable emergency unlock", afterSnapshot.skipSnapshot.eligible)
        assertEquals(1L, afterSnapshot.skipSnapshot.remaining)
    }

    @Test
    fun duplicateRecordingIdIsRejected() {
        val now = wallMs(2026, 7, 10, 11, 0)
        val initial = engine.normalize(PersistedState(), now, zone)

        val res1 = engine.recordSprintRecording(
            input = initial,
            recordingId = "sprint-dedup",
            qualifyingMeters = 120L,
            manualCompletionAward = false,
            nowWallMs = now,
            zoneId = zone,
        )
        val state = (res1 as CommandResult.Applied).state

        val res2 = engine.recordSprintRecording(
            input = state,
            recordingId = "sprint-dedup",
            qualifyingMeters = 120L,
            manualCompletionAward = false,
            nowWallMs = now + 1000L,
            zoneId = zone,
        )
        assertTrue(res2 is CommandResult.Rejected)
        assertEquals("Reward was already credited", (res2 as CommandResult.Rejected).reason)
    }

    @Test
    fun manualCompletionConfirmationAwardsOneSetWithoutDoubleCrediting() {
        val now = wallMs(2026, 7, 10, 11, 0)
        val initial = engine.normalize(PersistedState(), now, zone)

        // Previous remainder: 40m
        val stateWithRemainder = initial.copy(
            dailyCounter = initial.dailyCounter.copy(sprintMetersRemainder = 40L),
        )

        // Manual completion confirmation awards 1 set
        val result = engine.recordSprintRecording(
            input = stateWithRemainder,
            recordingId = "sprint-manual-1",
            qualifyingMeters = 0L,
            manualCompletionAward = true,
            nowWallMs = now,
            zoneId = zone,
        )
        assertTrue(result is CommandResult.Applied)
        val updated = (result as CommandResult.Applied).state

        // Exactly 1 set awarded (+3 min)
        assertEquals(1L, updated.dailyCounter.earnedBudgetRounds)
        assertEquals(1L, updated.dailyCounter.spendableBudgetRounds)
        // Remainder remains 40m without double-crediting
        assertEquals(40L, updated.dailyCounter.sprintMetersRemainder)
        assertEquals(listOf("sprint-manual-1"), updated.dailyCounter.creditedExerciseVerificationIds)

        // Cannot replay manual completion with same ID
        val replay = engine.recordSprintRecording(
            input = updated,
            recordingId = "sprint-manual-1",
            qualifyingMeters = 0L,
            manualCompletionAward = true,
            nowWallMs = now + 1000L,
            zoneId = zone,
        )
        assertTrue(replay is CommandResult.Rejected)
    }

    @Test
    fun zeroMetersWithoutManualAwardLeavesCountersUnchanged() {
        val now = wallMs(2026, 7, 10, 11, 0)
        val initial = engine.normalize(PersistedState(), now, zone)

        val res = engine.recordSprintRecording(
            input = initial,
            recordingId = "sprint-zero",
            qualifyingMeters = 0L,
            manualCompletionAward = false,
            nowWallMs = now,
            zoneId = zone,
        )
        assertTrue(res is CommandResult.Applied)
        val state = (res as CommandResult.Applied).state
        assertEquals(0L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(0L, state.dailyCounter.sprintMetersRemainder)
    }
}
