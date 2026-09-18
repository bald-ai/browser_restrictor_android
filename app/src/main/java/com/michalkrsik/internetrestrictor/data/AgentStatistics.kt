package com.michalkrsik.internetrestrictor.data

import com.michalkrsik.internetrestrictor.domain.*
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** A bounded view of records the phone actually keeps. Detailed history is fetched
 * by the assistant when needed; only summary and recent task events are preloaded. */
object AgentStatistics {
    fun build(snapshot: StateSnapshot, zoneId: ZoneId = ZoneId.systemDefault()): JSONObject {
        val now = snapshot.wallNowMs
        val since = (now - 14 * DAY_MS).coerceAtLeast(0)
        val settings = snapshot.configuration.settings
        val dayKey = PeriodCalculator.keysAt(now, zoneId, settings).daily
        val firstDay = LocalDate.parse(dayKey).minusDays(13).toString()
        val budget = snapshot.budgetSnapshots.firstOrNull()
        val days = snapshot.usageReports.filter { it.localDayKey in firstDay..dayKey }.sortedBy { it.localDayKey }
        val sessions = snapshot.recentActivityEvents.filter {
            it.type == ActivityEventType.BROWSER_SESSION && it.endedAtWallMs >= since && it.startedAtWallMs <= now
        }.sortedBy { it.startedAtWallMs }
        val receipts = snapshot.taskReceipts.filter {
            it.measurement.finishedAtWallMs in since..now
        }.sortedBy { it.measurement.finishedAtWallMs }
        val corrections = snapshot.recentCorrectionEvents.filter {
            it.correctedAtWallMs in since..now
        }.sortedBy { it.correctedAtWallMs }
        val summary = JSONObject()
            .put("appliedRevision", snapshot.appliedConfigRevision).put("observedAt", now)
            .put("creditsEarnedToday", snapshot.earnedBudgetRoundsToday).put("partialCredit", snapshot.taskCreditProgress)
            .put("remainingBrowsingMinutes", (budget?.remainingMs ?: 0) / 60_000.0)
            .put("usedBrowsingMinutes", (budget?.usedMs ?: 0) / 60_000.0)
            .put("grantedBrowsingMinutesThisBudget", budget?.limitMinutes ?: 0)
            .put("recordedBrowsingMinutesToday", snapshot.todayUsageReport.protectedUsageMs / 60_000.0)
            .put("emergencyUnlocksUsedToday", snapshot.skipSnapshot.used)
            .put("gateState", snapshot.canonicalMode.name).put("protectionHealth", snapshot.enforcementHealth.name)
            .put("dayReset", settings.dayReset).put("dailyBudgetMinutes", settings.dailyBudgetMinutes).put("timeZone", zoneId.id)
            .put("nextDayResetAt", PeriodCalculator.nextDailyReset(now, zoneId, settings))
            .put("lastAppliedRewardResetAt", snapshot.lastRewardResetWallMs.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("lastAppliedDayResetAt", snapshot.lastDailyRewardResetWallMs.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("historyAvailable", true)
        val events = JSONArray(receipts.takeLast(20).map { receipt ->
            JSONObject().put("task", receipt.measurement.task.name).put("value", BigDecimal(receipt.valueText))
                .put("metric", receipt.measurement.task.metric).put("at", receipt.measurement.finishedAtWallMs)
                .put("awardedMinutes", receipt.awardedMinutes).put("corrected", receipt.corrected)
        })
        val history = JSONObject().put("windowDays", 14).put("since", since).put("through", now).put("timeZone", zoneId.id)
            .put("dailyUsage", JSONArray(days.takeLast(14).map { day ->
                JSONObject().put("dayKey", day.localDayKey).put("recordedBrowsingMs", day.protectedUsageMs)
                    .put("earnedCredits", day.exerciseSets)
            }))
            .put("browserSessions", JSONArray(sessions.takeLast(80).map { event ->
                JSONObject().put("id", event.id).put("dayKey", event.localDayKey)
                    .put("startedAt", event.startedAtWallMs).put("endedAt", event.endedAtWallMs)
                    .put("recordedUsageMs", event.valueMs)
            }))
            .put("taskReceipts", JSONArray(receipts.takeLast(60).map { saved ->
                val receipt = saved.measurement
                JSONObject().put("id", receipt.id).put("taskId", receipt.taskId).put("task", receipt.task.name)
                    .put("metric", receipt.task.metric).put("verifier", receipt.task.verifier)
                    .put("measuredUnits", BigDecimal(receipt.measuredUnitsText)).put("effectiveUnits", BigDecimal(saved.valueText))
                    .put("startedAt", receipt.startedAtWallMs).put("endedAt", receipt.finishedAtWallMs)
                    .put("dayKey", saved.dayKey).put("configRevision", receipt.configRevision)
                    .put("unitsPerCredit", receipt.task.unitsPerCredit).put("minutesPerCredit", receipt.minutesPerCredit)
                    .put("awardedCredits", saved.awardedCredits).put("awardedMinutes", saved.awardedMinutes)
                    .put("rewardEligibleWhenApplied", saved.rewardEligible).put("corrected", saved.corrected)
                    .put("minutesRewardEligibleWhenApplied", saved.minutesRewardEligible)
                    .put("manualCompletion", receipt.manualCompletion).put("flagged", saved.flagged)
            }))
            .put("corrections", JSONArray(corrections.takeLast(30).map { correction ->
                JSONObject().put("receiptId", correction.receiptId).put("at", correction.correctedAtWallMs)
                    .put("originalType", correction.originalType).put("correctedType", correction.correctedType)
                    .put("originalStoredWholeUnits", correction.originalValue).put("correctedStoredWholeUnits", correction.correctedValue)
            }))
            .put("lastRewardResetAt", snapshot.lastRewardResetWallMs.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("lastDayResetAt", snapshot.lastDailyRewardResetWallMs.takeIf { it > 0 } ?: JSONObject.NULL)
            .put("truncated", JSONObject().put("browserSessions", sessions.size > 80)
                .put("taskReceipts", receipts.size > 60).put("corrections", corrections.size > 30))
            .put("limitations", JSONArray(listOf(
                "Browsing history is recorded foreground use, including emergency access. Individual records do not identify charged minutes, emergency mode, app, or website.",
                "usedBrowsingMinutes is charged time in the current budget only. Daily reports cover recorded reset-day totals; missing days are unknown, not zero.",
                "Only the latest applied reset timestamps are stored. Historical expired balances and exact minutes removed at each reset are not recorded.",
                "Nearby browsing intervals can be merged; recordedUsageMs is the actual recorded use and may be shorter than endedAt minus startedAt.",
                "Task rewards reflect saved corrections. Correction audit values store whole units; task receipt measurements preserve current decimal precision.",
            )))
        return JSONObject().put("summary", summary).put("events", events).put("history", history)
    }
}
