package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class DailyBudgetTest {
    private val engine = RestrictionEngine()
    private val zone = ZoneId.of("Europe/Prague")
    private val noon = at("2026-09-16T12:00:00+02:00[Europe/Prague]")
    private val reset = at("2026-09-17T04:00:00+02:00[Europe/Prague]")
    private fun at(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()
    private fun applied(result: CommandResult) = (result as CommandResult.Applied).state
    private fun set(state: PersistedState, minutes: Long, now: Long = noon, time: String = state.configuration.settings.dayReset) =
        applied(engine.applyConfiguration(state, state.configuration.copy(revision = state.configuration.revision + 1,
            settings = state.configuration.settings.copy(dailyBudgetMinutes = minutes, dayReset = time)), now, zone))
    private fun limit(state: PersistedState) = engine.budgetSnapshots(state).single().limitMinutes
    private fun earn(state: PersistedState, now: Long, id: String = "task") = applied(engine.applyTaskReceipt(state,
        TaskMeasurementReceipt(id, "pushups", state.configuration.revision, STARTER_TASKS.first(), 3, now - 1000, now,
            dayReset = state.configuration.settings.dayReset), now, zone))

    @Test fun allowanceChangesOnlyAtResetAndDoesNotAccumulateAcrossDays() {
        var state = set(PersistedState(), 20)
        assertEquals(0L, limit(state))
        state = earn(state, noon)
        state = state.copy(dailyUsage = state.dailyUsage.copy(usedMs = MINUTE_MS))
        state = set(state, 30)
        assertEquals(3L, limit(state))
        assertEquals(MINUTE_MS, state.dailyUsage.usedMs)
        assertEquals(3L, limit(engine.normalize(state, reset - 1, zone)))
        state = engine.normalize(state, reset, zone)
        assertEquals(30L, limit(state))
        assertEquals(0L, state.dailyUsage.usedMs)
        assertEquals(0L, state.dailyCounter.earnedBudgetRounds)
        assertEquals(0L, engine.snapshot(state, RuntimeSignals(), reset, zone).skipSnapshot.remaining)
        assertEquals(30L, limit(engine.normalize(state, reset + 1, zone)))
        state = earn(state, reset + 10_000, "next-task")
        assertEquals(33L, limit(state)) // The cap applies to the allowance, not earned time.
        state = set(state, 5, reset + 20_000)
        assertEquals(33L, limit(state))
        state = engine.normalize(state, reset + 3 * DAY_MS, zone)
        assertEquals(5L, limit(state)) // Several days offline grant one allowance.
        state = set(state, 0, reset + 3 * DAY_MS)
        assertEquals(5L, limit(state))
        assertEquals(0L, limit(engine.normalize(state, reset + 4 * DAY_MS, zone)))
    }

    @Test fun allowanceSurvivesTaskCorrectionsAndConfigurationRetries() {
        var state = engine.normalize(set(PersistedState(), 15), reset, zone)
        state = earn(state, reset + 1000)
        state = state.copy(dailyUsage = state.dailyUsage.copy(usedMs = 2 * MINUTE_MS))
        state = applied(engine.correctTaskReceipt(state, "task", 0, reset + 2000, zone))
        assertEquals(15L, limit(state))
        assertEquals(13 * MINUTE_MS, engine.budgetSnapshots(state).single().remainingMs)
        val retry = applied(engine.applyConfiguration(state, state.configuration, reset + 2000, zone))
        assertEquals(state, retry)
    }

    @Test fun changingResetTimePreservesBalanceAndGrantsOnlyAtTheNextBoundary() {
        var state = earn(set(PersistedState(), 12), noon)
        state = set(state, 20, time = "13:00")
        assertEquals(3L, limit(engine.normalize(state, noon + 59 * MINUTE_MS, zone)))
        state = engine.normalize(state, noon + HOUR_MS, zone)
        assertEquals(20L, limit(state))
        state = state.copy(dailyUsage = state.dailyUsage.copy(usedMs = MINUTE_MS))
        state = set(state, 30, noon + HOUR_MS, "04:00")
        assertEquals(20L, limit(engine.normalize(state, noon + HOUR_MS + 1, zone)))
        assertEquals(MINUTE_MS, state.dailyUsage.usedMs)
        assertEquals(30L, limit(engine.normalize(state, reset, zone)))
    }

    @Test fun invalidAllowanceIsRejectedWithoutChangingBalanceOrSettings() {
        val state = set(PersistedState(), 15)
        for (minutes in listOf(-1L, 31L, Long.MAX_VALUE)) {
            val result = engine.applyConfiguration(state, state.configuration.copy(revision = 2,
                settings = state.configuration.settings.copy(dailyBudgetMinutes = minutes)), noon, zone)
            assertTrue(result is CommandResult.Rejected)
        }
        assertNull(ConfigurationSettings(dailyBudgetMinutes = 0).validationError())
        assertNull(ConfigurationSettings(dailyBudgetMinutes = 30).validationError())
    }

    @Test fun usageSpanningDailyResetChargesEachDaysBudget() {
        var state = set(PersistedState(), 10)
        state = earn(state, noon)
        state = state.copy(activeUnlock = UnlockSession("session", UnlockSource.NORMAL, reset - 1000, reset + MINUTE_MS))
        val runtime = RuntimeSignals(foregroundClass = ForegroundClass.BROWSER, activeTarget = ProtectedTarget.BROWSER_POOL,
            accessibilityEnabled = true, serviceConnected = true, storageReady = true)
        val after = engine.consumeUsage(state, runtime, reset - 500, reset + 500, zone)
        assertEquals(10L, limit(after))
        assertEquals(500L, after.dailyUsage.usedMs)
        assertEquals(500L, after.usageReports.single { it.localDayKey == "2026-09-16" }.protectedUsageMs)
        assertEquals(500L, after.usageReports.single { it.localDayKey == "2026-09-17" }.protectedUsageMs)
    }

    @Test fun dstRepeatedHourDoesNotGrantTheAllowanceTwice() {
        val before = at("2026-10-25T02:15:00+02:00[Europe/Prague]")
        val first = at("2026-10-25T02:45:00+02:00[Europe/Prague]")
        val second = at("2026-10-25T02:45:00+01:00[Europe/Prague]")
        var state = set(PersistedState(), 30, before, "02:30")
        state = engine.normalize(state, first, zone)
        assertEquals(30L, limit(state))
        state = state.copy(dailyUsage = state.dailyUsage.copy(usedMs = MINUTE_MS))
        state = engine.normalize(state, second, zone)
        assertEquals(29 * MINUTE_MS, engine.budgetSnapshots(state).single().remainingMs)
    }
}
