package com.michalkrsik.internetrestrictor.data

import com.michalkrsik.internetrestrictor.domain.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class V1StateMigrationTest {
    private val now = ZonedDateTime.parse("2026-09-16T12:00:00+02:00[Europe/Prague]").toInstant().toEpochMilli()
    private val zone = ZoneId.of("Europe/Prague")

    @Test fun schemaFourteenMigratesHistoryCorrectionsEarningsAndPresets() = runTest {
        val state = PersistedState(stateRevision = 71, settings = RestrictorSettings(sessionMinutes = listOf(2, 7, 40)),
            dailyCounter = DailyCounter(localDayKey = "2026-09-16", earnedBudgetRounds = 3, spendableBudgetRounds = 2, exercisePointSixths = 18),
            dailyUsage = UsagePeriodState(BudgetPeriod.DAILY, "2026-09-16", MINUTE_MS),
            correctionHistory = listOf(CorrectionEvent("correction", "receipt", now, "push_up", 3, "push_up", 5)),
            usageReports = listOf(DailyUsageReport("2026-09-16", protectedUsageMs = MINUTE_MS, exerciseSets = 3)))
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val sectionLength = ConfigurationJson.stateJson(state).toString().toByteArray(Charsets.UTF_8).size
        val legacy = output.toByteArray().copyOf(output.size() - sectionLength - 4)
        ByteBuffer.wrap(legacy).putInt(4, 14)
        val migrated = AppStateSerializer.readFrom(ByteArrayInputStream(legacy))
        assertEquals(RESTRICTOR_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(state.dailyCounter, migrated.dailyCounter)
        assertEquals(state.dailyUsage, migrated.dailyUsage)
        assertEquals(state.correctionHistory, migrated.correctionHistory)
        assertEquals(state.settings.sessionMinutes, migrated.configuration.settings.sessionMinutes)
        assertEquals(state.usageReports, migrated.usageReports)
        assertFalse(migrated.hasAppliedServerConfiguration)
    }

    @Test fun v1ConfigFractionsAndReceiptDedupSurviveRestart() = runTest {
        val engine = RestrictionEngine()
        var state = (engine.applyConfiguration(PersistedState(), AppConfiguration(), now, zone) as CommandResult.Applied).state
        val task = TaskDefinition("thirds", "Thirds", "video", "repetitions", 3, 1, "Count repetitions")
        val receipt = TaskMeasurementReceipt("receipt", "thirds", 0, task, 1, now - 10000, now)
        state = (engine.applyTaskReceipt(state, receipt, now, zone) as CommandResult.Applied).state
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(state, restored)
        assertTrue(restored.hasAppliedServerConfiguration)
        assertEquals("1", restored.taskCreditProgress.numerator)
        assertEquals("3", restored.taskCreditProgress.denominator)
        assertEquals(restored, (engine.applyTaskReceipt(restored, receipt, now, zone) as CommandResult.Applied).state)
    }

    @Test fun serverConfigAndReceiptJsonMatchCanonicalContract() {
        val config = AppConfiguration(revision = 4)
        val envelope = org.json.JSONObject().put("revision", 4).put("config", ConfigurationJson.configurationJson(config))
        assertEquals(config, ConfigurationJson.parseConfiguration(envelope))
        val task = STARTER_TASKS.first()
        val receipt = TaskMeasurementReceipt("receipt", task.id, 4, task, 4, now - 10000, now,
            correctedUnits = 5, attemptId = "attempt")
        assertEquals(receipt, ConfigurationJson.parseTaskReceipt(ConfigurationJson.taskReceiptJson(receipt)))
    }

    @Test fun fractionalMeasurementSurvivesNetworkAndStorageRoundtrip() = runTest {
        val task = STARTER_TASKS.first { it.id == "plank" }
        val receipt = TaskMeasurementReceipt("receipt", task.id, 4, task, 1, now - 10000, now, measuredUnitsText = "1.7", maximumCorrectableUnits = 9.9)
        val parsed = ConfigurationJson.parseTaskReceipt(ConfigurationJson.taskReceiptJson(receipt))
        assertEquals(receipt, parsed)
        val engine = RestrictionEngine()
        var state = (engine.applyConfiguration(PersistedState(), AppConfiguration(), now, zone) as CommandResult.Applied).state
        state = (engine.applyTaskReceipt(state, receipt, now, zone) as CommandResult.Applied).state
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(state, restored)
        assertEquals("1.7", restored.taskReceipts.single().valueText)
    }

    @Test fun delayedDailyCreditWithoutMinutesKeepsEligibilityAndDedupAfterRestart() = runTest {
        val engine = RestrictionEngine()
        val before = ZonedDateTime.parse("2026-09-16T19:59:00+02:00[Europe/Prague]").toInstant().toEpochMilli()
        val after = before + 2 * MINUTE_MS
        var state = (engine.applyConfiguration(PersistedState(), AppConfiguration(), before, zone) as CommandResult.Applied).state
        val task = STARTER_TASKS.first { it.id == "pushups" }.copy(unitsPerCredit = 5)
        val receipt = TaskMeasurementReceipt("late-daily", task.id, 0, task, 8, before - 10000, before, minutesRewardEligible = false)
        assertEquals(receipt, ConfigurationJson.parseTaskReceipt(ConfigurationJson.taskReceiptJson(receipt)))
        state = (engine.applyTaskReceipt(state, receipt, after, zone) as CommandResult.Applied).state
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(state, restored)
        assertTrue(restored.taskReceipts.single().rewardEligible)
        assertFalse(restored.taskReceipts.single().minutesRewardEligible)
        assertEquals(1L, restored.dailyCounter.earnedBudgetRounds)
        assertEquals(0L, restored.dailyCounter.spendableBudgetRounds)
        assertEquals(0L, engine.budgetSnapshots(restored).single().limitMinutes)
        assertEquals("3", restored.taskCreditProgress.numerator)
        assertEquals("5", restored.taskCreditProgress.denominator)
        assertTrue(restored.lastDailyRewardResetWallMs > 0)
        assertEquals(restored, (engine.applyTaskReceipt(restored, receipt, after, zone) as CommandResult.Applied).state)
    }
    @Test fun allowanceAndFrozenBalanceSurviveRestartAndOldSettingsDefaultToZero() = runTest {
        val engine = RestrictionEngine()
        var state = (engine.applyConfiguration(PersistedState(), AppConfiguration(
            settings = ConfigurationSettings(dailyBudgetMinutes = 20)), now, zone) as CommandResult.Applied).state
        assertEquals(0L, engine.budgetSnapshots(state).single().limitMinutes)
        val output = ByteArrayOutputStream()
        AppStateSerializer.writeTo(state, output)
        val restored = AppStateSerializer.readFrom(ByteArrayInputStream(output.toByteArray()))
        assertEquals(state, restored)
        assertEquals(20L, restored.configuration.settings.dailyBudgetMinutes)
        assertEquals(0L, engine.budgetSnapshots(engine.normalize(restored, now + MINUTE_MS, zone)).single().limitMinutes)
        assertEquals(20L, engine.budgetSnapshots(engine.normalize(restored, now + DAY_MS, zone)).single().limitMinutes)

        val legacyJson = ConfigurationJson.stateJson(restored)
        legacyJson.getJSONObject("config").getJSONObject("settings").apply {
            remove("dailyBudgetMinutes")
            put("budgetReset", "20:00")
        }
        val legacy = ConfigurationJson.withStateJson(restored, legacyJson)
        assertEquals(0L, legacy.configuration.settings.dailyBudgetMinutes)
        assertEquals(restored.dailyCounter, legacy.dailyCounter)
        assertFalse(ConfigurationJson.configurationJson(legacy.configuration).getJSONObject("settings").has("budgetReset"))
    }

    @Test fun allowanceJsonRejectsOutOfRangeAndFractionalValues() {
        for (value in listOf(-1, 31, 10.5, "999999999999999999999999")) {
            val json = ConfigurationJson.configurationJson(AppConfiguration())
            json.getJSONObject("settings").put("dailyBudgetMinutes", value)
            assertTrue("Rejected $value", runCatching { ConfigurationJson.parseConfiguration(json) }.isFailure)
        }
    }

}
