package com.michalkrsik.internetrestrictor.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class StateNormalizationTest {
    private val engine = RestrictionEngine()
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-09-24T12:00:00Z").toEpochMilli()
    private fun normalized() = engine.normalize(PersistedState(), now, zone)

    @Test fun canonicalizationIsIdempotentAndPreservesNonPolicyPreferences() {
        val input = normalized().copy(settings = RestrictorSettings(enforcementEnabled = false,
            pauseUntilWallMs = now + HOUR_MS, delayEnabled = false, emergencySkipsEnabled = false,
            emergencySkipDailyLimit = 9, theme = ThemePreference.LIGHT, sessionMinutes = listOf(7)),
            offUsageReminder = OffUsageReminderState(1, 10, true, 10, 10), currentVisitGraceActive = true)
        val result = engine.normalize(input, now, zone)
        assertEquals(input.stateRevision + 1, result.stateRevision)
        assertEquals(RestrictorSettings(theme = ThemePreference.LIGHT, sessionMinutes = listOf(7)), result.settings)
        assertEquals(OffUsageReminderState(), result.offUsageReminder)
        assertFalse(result.currentVisitGraceActive)
        assertEquals(result, engine.normalize(result, now, zone))
    }

    @Test fun resetPrecedesLegacyMigrationAndKeepsPreviousDayEarnedHistory() {
        val input = normalized().copy(dailyCounter = DailyCounter(localDayKey = "2026-09-23", earnedBudgetRounds = 2,
            spendableBudgetRounds = 2, pushupRepetitionRemainder = 5), taskBudgetKey = "old",
            taskCreditProgress = CreditFraction("1", "2"), taskProgressBase = CreditFraction("1", "2"))
        val result = engine.normalize(input, now, zone)
        assertEquals(0L, result.dailyCounter.earnedBudgetRounds)
        assertEquals(0L, result.dailyCounter.pushupRepetitionRemainder)
        assertEquals("", result.taskBudgetKey)
        assertEquals(CreditFraction(), result.taskCreditProgress)
        assertEquals(CreditFraction(), result.taskProgressBase)
        assertEquals(2L, result.usageReports.single { it.localDayKey == "2026-09-23" }.exerciseSets)
        assertEquals(Instant.parse("2026-09-24T04:00:00Z").toEpochMilli(), result.lastRewardResetWallMs)
    }

    @Test fun sameDayLegacyRemaindersMigrateOnceAndRespectSafeBudgetLimit() {
        val base = normalized()
        val input = base.copy(dailyCounter = base.dailyCounter.copy(pushupRepetitionRemainder = 3,
            plankSecondRemainder = 3, machineRepetitionRemainder = 3))
        val result = engine.normalize(input, now, zone)
        assertEquals(1L, result.dailyCounter.earnedBudgetRounds)
        assertEquals(1L, result.dailyCounter.spendableBudgetRounds)
        assertEquals(3L, result.dailyCounter.exercisePointSixths)
        assertEquals(0L, result.dailyCounter.pushupRepetitionRemainder)
        assertEquals(0L, result.dailyCounter.plankSecondRemainder)
        assertEquals(0L, result.dailyCounter.machineRepetitionRemainder)
        assertEquals(result, engine.normalize(result, now, zone))
        val capped = input.copy(dailyCounter = input.dailyCounter.copy(earnedBudgetRounds = MAX_SAFE_BUDGET_ROUNDS))
        val refused = engine.normalize(capped, now, zone)
        assertEquals(capped.dailyCounter, refused.dailyCounter)
    }

    @Test fun accessExpiresExactlyAtTheBoundaryAndClockRollbackInvalidatesWaits() {
        val base = normalized()
        val unlock = UnlockSession("unlock", UnlockSource.NORMAL, now - 100, now)
        assertNull(engine.normalize(base.copy(activeUnlock = unlock), now, zone).activeUnlock)
        assertEquals(unlock, engine.normalize(base.copy(activeUnlock = unlock), now - 1, zone).activeUnlock)
        val wait = PendingWait("wait", MINUTE_MS, 12000, 0, now, now)
        assertNull(engine.normalize(base.copy(pendingWait = wait), now - 1, zone).pendingWait)
        assertEquals(wait, engine.normalize(base.copy(pendingWait = wait), now + PENDING_WAIT_EXPIRY_MS - 1, zone).pendingWait)
        assertNull(engine.normalize(base.copy(pendingWait = wait), now + PENDING_WAIT_EXPIRY_MS, zone).pendingWait)
    }

    @Test fun historyCutoffIsInclusiveAndEventLimitKeepsNewestEvents() {
        val base = normalized()
        val events = (0..520).map { index -> ActivityEvent("$index", "2026-09-24", ActivityEventType.BROWSER_SESSION, index.toLong(), index + 1L, 1) }
        val input = base.copy(usageReports = listOf(DailyUsageReport("2026-08-24"), DailyUsageReport("2026-08-25", offUsageMs = 50)),
            activityEvents = events.reversed() + ActivityEvent("old", "2026-08-24", ActivityEventType.EXERCISE_SET, 0, 0, 0))
        val result = engine.normalize(input, now, zone)
        assertEquals(listOf(DailyUsageReport("2026-08-25")), result.usageReports)
        assertEquals(events.takeLast(512), result.activityEvents)
        assertEquals(input.stateRevision + 1, result.stateRevision)
    }
}
