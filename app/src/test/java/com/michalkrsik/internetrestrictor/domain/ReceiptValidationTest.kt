package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ReceiptValidationTest(private val receipt: TaskMeasurementReceipt, private val message: String) {
    @Test fun invalidReceiptCannotChangeRewardsOrHistory() {
        val engine = RestrictionEngine()
        val zone = ZoneId.of("UTC")
        val state = (engine.applyConfiguration(PersistedState(), AppConfiguration(), 100_000, zone) as CommandResult.Applied).state
        assertEquals(message, receipt.validationError(100_000))
        val result = engine.applyTaskReceipt(state, receipt, 100_000, zone)
        assertTrue(result is CommandResult.Rejected)
        assertEquals(message, (result as CommandResult.Rejected).reason)
        assertEquals(state, result.state)
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{index}: {1}") fun cases(): List<Array<Any>> {
            val task = STARTER_TASKS.first()
            val base = TaskMeasurementReceipt("receipt", task.id, 0, task, 1, 90_000, 100_000)
            val cases = mutableListOf<Array<Any>>()
            fun rejects(message: String, vararg receipts: TaskMeasurementReceipt) {
                receipts.forEach { cases += arrayOf(it, message) }
            }
            rejects("Invalid verification receipt ID", base.copy(id = ""), base.copy(id = "x".repeat(129)),
                base.copy(attemptId = ""), base.copy(attemptId = "x".repeat(129)))
            rejects("Invalid pinned task definition", base.copy(taskId = "wrong"), base.copy(task = task.copy(name = "")))
            rejects("Receipt has not been verified", base.copy(configRevision = -1), base.copy(verified = false))
            rejects("Receipt measurement or reward is outside the supported range", base.copy(value = -1),
                base.copy(value = 1_000_001), base.copy(minutesPerCredit = 0), base.copy(minutesPerCredit = 121))
            rejects("Invalid precise receipt measurement", base.copy(measuredUnitsText = "bad"),
                base.copy(measuredUnitsText = "2"), base.copy(measuredUnitsText = "-1"),
                base.copy(measuredUnitsText = "1e-129"), base.copy(measuredUnitsText = "0".repeat(257)),
                base.copy(measuredUnitsText = "1000001"))
            rejects("Repetitions must be a whole count", base.copy(measuredUnitsText = "1.5"))
            rejects("Invalid precise corrected measurement", base.copy(correctedUnitsText = "bad"),
                base.copy(correctedUnits = 1, correctedUnitsText = "2"), base.copy(correctedUnitsText = "1"),
                base.copy(correctedUnits = 100001, correctedUnitsText = "100001"))
            rejects("Corrected receipt measurement is outside the supported range", base.copy(correctedUnits = -1, correctedUnitsText = null),
                base.copy(correctedUnits = 1_000_001, correctedUnitsText = null))
            val seconds = base.copy(task = task.copy(metric = "seconds"))
            rejects("Invalid recording correction maximum", base.copy(maximumCorrectableUnits = 1.0),
                seconds.copy(maximumCorrectableUnits = Double.NaN), seconds.copy(maximumCorrectableUnits = -0.1),
                seconds.copy(maximumCorrectableUnits = 30.51), seconds.copy(task = STARTER_TASKS.last(), taskId = "walk", maximumCorrectableUnits = 1.0))
            rejects("Manual completion needs an eligible GPS receipt", base.copy(manualCompletion = true),
                base.copy(manualCompletion = true, fallbackEligible = true))
            rejects("Invalid recording timestamps", base.copy(startedAtWallMs = -1), base.copy(finishedAtWallMs = 89_999),
                base.copy(finishedAtWallMs = 100_001))
            rejects("Invalid receipt reset settings", base.copy(dayReset = "24:00"), base.copy(timeZone = "invalid-zone"))
            return cases
        }
    }
}
