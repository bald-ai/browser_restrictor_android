package com.michalkrsik.internetrestrictor.data

import com.michalkrsik.internetrestrictor.domain.*
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class AgentStatisticsTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val now = ZonedDateTime.parse("2026-09-16T12:00:00+02:00[Europe/Prague]").toInstant().toEpochMilli()
    private val engine = RestrictionEngine()
    private fun snapshot() = engine.snapshot(engine.normalize(PersistedState(), now, zone), RuntimeSignals(), now, zone)

    @Test fun preservesActualBrowsingIntervalsAndDistinguishesChargedBudgetFromRecordedUse() {
        val snapshot = snapshot().copy(
            usageReports = listOf(DailyUsageReport("2026-09-01", 99 * MINUTE_MS), DailyUsageReport("2026-09-15", 8 * MINUTE_MS, exerciseSets = 2)),
            todayUsageReport = DailyUsageReport("2026-09-16", 6 * MINUTE_MS),
            budgetSnapshots = snapshot().budgetSnapshots.map { it.copy(usedMs = 2 * MINUTE_MS, remainingMs = MINUTE_MS, limitMinutes = 3) },
            recentActivityEvents = listOf(
                ActivityEvent("session", "2026-09-16", ActivityEventType.BROWSER_SESSION, now - 10 * MINUTE_MS, now, 6 * MINUTE_MS),
                ActivityEvent("earned", "2026-09-16", ActivityEventType.EXERCISE_SET, now - 5_000, now, 3 * MINUTE_MS),
                ActivityEvent("old", "2026-09-01", ActivityEventType.BROWSER_SESSION, now - 15 * DAY_MS, now - 15 * DAY_MS + MINUTE_MS, MINUTE_MS),
            ), lastRewardResetWallMs = now - 8 * 60 * MINUTE_MS,
        )
        val payload = AgentStatistics.build(snapshot, zone)
        assertEquals(2.0, payload.getJSONObject("summary").getDouble("usedBrowsingMinutes"), 0.0)
        assertEquals(6.0, payload.getJSONObject("summary").getDouble("recordedBrowsingMinutesToday"), 0.0)
        val history = payload.getJSONObject("history")
        assertEquals(1, history.getJSONArray("dailyUsage").length()) // Missing days stay unknown.
        assertEquals("2026-09-15", history.getJSONArray("dailyUsage").getJSONObject(0).getString("dayKey"))
        val sessions = history.getJSONArray("browserSessions")
        assertEquals(1, sessions.length())
        assertEquals(now - 10 * MINUTE_MS, sessions.getJSONObject(0).getLong("startedAt"))
        assertEquals(6 * MINUTE_MS, sessions.getJSONObject(0).getLong("recordedUsageMs"))
        assertFalse(sessions.getJSONObject(0).has("chargedMinutes"))
        assertTrue(history.getJSONArray("limitations").toString().contains("including emergency access"))
        assertEquals(snapshot.lastRewardResetWallMs, history.getLong("lastRewardResetAt"))
    }

    @Test fun boundsPayloadAndKeepsExactTaskMeasurementsAndPinnedRewardRates() {
        val task = TaskDefinition("hold", "A".repeat(80), "video", "seconds", 3, 1, "Instructions should never bulk up history".repeat(40))
        val measurement = TaskMeasurementReceipt("receipt", "hold", 5, task, 1, now - 10_000, now,
            minutesPerCredit = 7, measuredUnitsText = "1.7")
        val base = SavedTaskReceipt(measurement, dayKey = "2026-09-16", budgetKey = "ledger", rewardEligible = true,
            valueText = "1.7", awardedMinutes = 7)
        val snapshot = snapshot().copy(
            taskReceipts = (0 until 90).map { base.copy(measurement = measurement.copy(id = "receipt-$it", finishedAtWallMs = now - 90 + it)) },
            recentActivityEvents = (0 until 100).map {
                ActivityEvent("session-$it", "2026-09-16", ActivityEventType.BROWSER_SESSION, now - 1000 + it, now - 900 + it, 100)
            },
            recentCorrectionEvents = (0 until 40).map { CorrectionEvent("correct-$it", "receipt-$it", now - 40 + it, "hold", 1, "hold", 2) },
        )
        val payload = AgentStatistics.build(snapshot, zone)
        val history = payload.getJSONObject("history")
        assertEquals(20, payload.getJSONArray("events").length())
        assertEquals("1.7", payload.getJSONArray("events").getJSONObject(0).get("value").toString())
        assertEquals(60, history.getJSONArray("taskReceipts").length())
        assertEquals(80, history.getJSONArray("browserSessions").length())
        assertEquals(30, history.getJSONArray("corrections").length())
        assertTrue(history.getJSONObject("truncated").getBoolean("taskReceipts"))
        assertTrue(history.getJSONObject("truncated").getBoolean("browserSessions"))
        assertTrue(history.getJSONObject("truncated").getBoolean("corrections"))
        val receipt = history.getJSONArray("taskReceipts").getJSONObject(0)
        assertEquals("1.7", receipt.get("measuredUnits").toString())
        assertEquals(7L, receipt.getLong("minutesPerCredit"))
        assertFalse(receipt.has("instructions"))
        assertTrue("Bounded history must fit statistics API", payload.toString().toByteArray().size < 100_000)
        assertFalse(payload.getJSONObject("summary").has("browserSessions"))
    }

    @Test fun snapshotPublishesRecentStoredHistoryAndLastAppliedReset() {
        val state = engine.normalize(PersistedState(), now, zone).copy(
            activityEvents = listOf(ActivityEvent("real-session", "2026-09-16", ActivityEventType.BROWSER_SESSION, now - 7000, now, 6000)),
            correctionHistory = listOf(CorrectionEvent("real-correction", "receipt", now, "pushups", 3, "pushups", 5)),
            lastRewardResetWallMs = now - 1000,
        )
        val snapshot = engine.snapshot(state, RuntimeSignals(), now, zone)
        assertEquals(state.activityEvents, snapshot.recentActivityEvents)
        assertEquals(state.correctionHistory, snapshot.recentCorrectionEvents)
        assertEquals(state.lastRewardResetWallMs, snapshot.lastRewardResetWallMs)
    }
}
