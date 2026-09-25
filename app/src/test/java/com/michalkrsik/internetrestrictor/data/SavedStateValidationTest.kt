package com.michalkrsik.internetrestrictor.data

import androidx.datastore.core.CorruptionException
import com.michalkrsik.internetrestrictor.domain.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.ByteArrayOutputStream

/** Invalid durable state must be rejected before even a partial replacement file is written. */
@RunWith(Parameterized::class)
class SavedStateValidationTest(private val description: String, private val state: PersistedState, private val expected: String) {
    @Test fun rejectsCorruptionBeforeWriting() = runTest {
        val output = ByteArrayOutputStream()
        val failure = runCatching { AppStateSerializer.writeTo(state, output) }.exceptionOrNull()
        assertTrue("$description: expected corruption, got $failure", failure is CorruptionException)
        assertEquals(description, expected, failure!!.message)
        assertEquals("$description: partial state was written", 0, output.size())
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}") fun cases(): List<Array<Any>> {
            val base = PersistedState()
            val result = mutableListOf<Array<Any>>()
            fun add(name: String, state: PersistedState, error: String) { result += arrayOf(name, state, error) }
            add("wrong schema", base.copy(schemaVersion = -1), "Wrong schema")
            add("negative revision", base.copy(stateRevision = -1), "Negative revision")
            for ((name, settings) in listOf(
                "disabled protection" to base.settings.copy(enforcementEnabled = false),
                "paused protection" to base.settings.copy(pauseUntilWallMs = 1L),
            )) add(name, base.copy(settings = settings), "Protection must remain enabled")
            add("disabled skips", base.copy(settings = base.settings.copy(emergencySkipsEnabled = false)), "Emergency skips must remain enabled")
            add("disabled wait", base.copy(settings = base.settings.copy(delayEnabled = false)), "The focused wait must remain enabled")
            add("changed skip limit", base.copy(settings = base.settings.copy(emergencySkipDailyLimit = 0)), "Invalid setting value")
            for (sessions in listOf(emptyList(), List(7) { 3L }, listOf(0L), listOf(121L)))
                add("invalid sessions $sessions", base.copy(settings = base.settings.copy(sessionMinutes = sessions)), "Invalid session presets")
            add("negative usage", base.copy(dailyUsage = base.dailyUsage.copy(usedMs = -1)), "Negative usage")
            val unlock = UnlockSession("u", UnlockSource.NORMAL, 10, 20)
            for ((name, value) in listOf("blank" to unlock.copy(id = " "), "negative start" to unlock.copy(grantedAtWallMs = -1), "reversed" to unlock.copy(expiresAtWallMs = 9)))
                add("unlock $name", base.copy(activeUnlock = value), "Invalid unlock")
            val wait = PendingWait("w", 1000, 100, 0, 10, 10)
            for ((name, value) in listOf("blank" to wait.copy(id = ""), "duration" to wait.copy(selectedDurationMs = 0), "focus" to wait.copy(requiredFocusMs = -1), "negative progress" to wait.copy(accumulatedFocusMs = -1), "excess progress" to wait.copy(accumulatedFocusMs = 101), "negative creation" to wait.copy(createdAtWallMs = -1), "reversed update" to wait.copy(updatedAtWallMs = 9)))
                add("wait $name", base.copy(pendingWait = value), "Invalid pending wait")
            add("overlapping access", base.copy(activeUnlock = unlock, pendingWait = wait), "Unlock and wait overlap")
            val receipt = ExerciseReceipt("receipt", "2026-09-24", 1, ExerciseId.PUSH_UP, 1,
                pointsBefore = 0, awardedSets = 0, legacyEveningResetApplied = false)
            for (value in listOf(receipt.copy(id = ""), receipt.copy(id = "a".repeat(129)),
                receipt.copy(dayKey = ""), receipt.copy(recordedAtWallMs = -1),
                receipt.copy(originalValue = 0), receipt.copy(value = -1),
                receipt.copy(originalExercise = ExerciseId.UNSUPPORTED), receipt.copy(exercise = ExerciseId.UNSUPPORTED),
                receipt.copy(pointsBefore = -1), receipt.copy(pointsBefore = EXERCISE_POINT_SIXTHS_PER_BOOST),
                receipt.copy(awardedSets = -1), receipt.copy(awardedSets = Long.MAX_VALUE)))
                add("exercise receipt ${result.size}", base.copy(lastExercise = value), "Invalid exercise receipt")
            val counter = base.dailyCounter
            val invalidCounters = listOf(
                counter.copy(exercisePointSixths = -1), counter.copy(exercisePointSixths = 30),
                counter.copy(earnedBudgetRounds = -1), counter.copy(earnedBudgetRounds = Long.MAX_VALUE),
                counter.copy(spendableBudgetRounds = -1), counter.copy(spendableBudgetRounds = 1),
                counter.copy(emergencySkipsUsed = -1), counter.copy(pushupRepetitionRemainder = -1), counter.copy(pushupRepetitionRemainder = 5),
                counter.copy(plankSecondRemainder = -1), counter.copy(plankSecondRemainder = 18),
                counter.copy(machineRepetitionRemainder = -1), counter.copy(machineRepetitionRemainder = 10),
                counter.copy(sprintMetersRemainder = -1), counter.copy(sprintMetersRemainder = 120),
                counter.copy(verifiedPushups = -1), counter.copy(creditedExerciseVerificationIds = List(257) { "receipt-$it" }),
                counter.copy(creditedExerciseVerificationIds = listOf("")), counter.copy(creditedExerciseVerificationIds = listOf("a".repeat(129))),
                counter.copy(creditedExerciseVerificationIds = listOf("same", "same")),
            )
            invalidCounters.forEachIndexed { i, value -> add("counter invariant $i", base.copy(dailyCounter = value), "Invalid daily counter") }
            for (value in listOf(counter.copy(grantedBudgetMinutes = -1), counter.copy(grantedBudgetMinutes = Long.MAX_VALUE)))
                add("budget overflow ${value.grantedBudgetMinutes}", base.copy(dailyCounter = value), "Invalid task accounting")
            add("invalid configuration", base.copy(configuration = base.configuration.copy(settings = base.configuration.settings.copy(waitSeconds = -1))), "Wait must be between 0 and 600 seconds")
            add("negative task reset", base.copy(lastRewardResetWallMs = -1), "Invalid task accounting")
            add("negative daily reset", base.copy(lastDailyRewardResetWallMs = -1), "Invalid task accounting")
            for (progress in listOf(CreditFraction("-1", "1"), CreditFraction("1", "0"), CreditFraction("1", "1"))) {
                add("credit remainder $progress", base.copy(taskCreditProgress = progress), "Invalid task accounting")
                add("base remainder $progress", base.copy(taskProgressBase = progress), "Invalid task accounting")
            }
            val task = AppConfiguration().tasks.first()
            val measurement = TaskMeasurementReceipt("task-receipt", task.id, 1, task, 1, 10, 20)
            val saved = SavedTaskReceipt(measurement, dayKey = "2026-09-24", budgetKey = "ledger", rewardEligible = true)
            for (value in listOf(saved.copy(measurement = measurement.copy(verified = false)),
                saved.copy(valueText = "bad"), saved.copy(valueText = "2"),
                saved.copy(value = 1_000_001, valueText = "1000001"),
                saved.copy(awardedCredits = -1), saved.copy(awardedMinutes = -1)))
                add("saved task ${result.size}", base.copy(taskReceipts = listOf(value)), "Invalid saved task receipt")
            add("duplicate task receipt", base.copy(taskReceipts = listOf(saved, saved)), "Invalid task accounting")
            add("full task history", base.copy(taskReceipts = List(10_001) { saved.copy(measurement = measurement.copy(id = "r$it")) }), "Invalid task accounting")
            val report = DailyUsageReport("2026-09-24")
            for (value in listOf(report.copy(localDayKey = ""), report.copy(protectedUsageMs = -1), report.copy(offUsageMs = 1), report.copy(exerciseSets = -1)))
                add("usage report $value", base.copy(usageReports = listOf(value)), "Invalid usage report")
            val event = ActivityEvent("e", "2026-09-24", ActivityEventType.entries.first(), 10, 20, 1)
            for (value in listOf(event.copy(id = ""), event.copy(localDayKey = ""), event.copy(startedAtWallMs = -1), event.copy(endedAtWallMs = 9), event.copy(valueMs = -1)))
                add("activity event $value", base.copy(activityEvents = listOf(value)), "Invalid activity event")
            val correction = CorrectionEvent("c", "r", 10, "push_up", 1, "push_up", 0)
            for (value in listOf(correction.copy(id = ""), correction.copy(id = "a".repeat(129)), correction.copy(receiptId = ""), correction.copy(receiptId = "a".repeat(129)), correction.copy(correctedAtWallMs = -1), correction.copy(originalType = ""), correction.copy(originalType = "a".repeat(129)), correction.copy(correctedType = ""), correction.copy(correctedType = "a".repeat(129)), correction.copy(originalValue = 0), correction.copy(correctedValue = -1)))
                add("correction ${result.size}", base.copy(correctionHistory = listOf(value)), "Invalid correction event")
            add("bad cycle date", base.copy(monthlyPushupCycles = listOf(MonthlyPushupCycle("not a date"))), "Invalid monthly push-up cycle date")
            for (cycles in listOf(listOf(MonthlyPushupCycle("2026-09-10")), listOf(MonthlyPushupCycle("2026-09-09", -1)), List(2) { MonthlyPushupCycle("2026-09-09") }, listOf(MonthlyPushupCycle("2026-09-09"), MonthlyPushupCycle("2026-08-09"))))
                add("cycle order or value $cycles", base.copy(monthlyPushupCycles = cycles), "Invalid monthly push-up cycle")
            val reminder = OffUsageReminderState()
            for (value in listOf(reminder.copy(sessionStartedAtWallMs = -1), reminder.copy(qualifyingUsageMs = -1), reminder.copy(lastNotificationWallMs = -1), reminder.copy(usageAtLastNotificationMs = -1), reminder.copy(usageAtLastNotificationMs = 1)))
                add("reminder $value", base.copy(offUsageReminder = value), "Invalid reminder state")
            return result
        }
    }
}
