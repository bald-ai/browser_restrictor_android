package com.michalkrsik.internetrestrictor.domain

import org.junit.Assert.*
import org.junit.Test

class ConfigurationValidationTest {
    @Test fun settingsBoundsAcceptEndpointsAndRejectOutsideValues() {
        val base = ConfigurationSettings()
        val fields: List<Triple<LongRange, String, (Long) -> ConfigurationSettings>> = listOf(
            Triple(0L..600L, "Wait", { base.copy(waitSeconds = it) }),
            Triple(1L..120L, "Credit reward", { base.copy(minutesPerCredit = it) }),
            Triple(0L..30L, "Daily budget", { base.copy(dailyBudgetMinutes = it) }),
            Triple(1L..100L, "YouTube", { base.copy(youtubeCredits = it) }),
            Triple(1L..120L, "Emergency", { base.copy(emergencyMinutes = it) }),
        )
        for ((range, label, change) in fields) {
            for (value in listOf(range.first, range.last)) assertNull("$label $value", change(value).validationError())
            for (value in listOf(range.first - 1, range.last + 1)) assertTrue(change(value).validationError()!!.startsWith(label))
        }
        for (sessions in listOf(emptyList(), listOf(1L, 2L), listOf(0L, 1L, 2L), listOf(1L, 2L, 121L)))
            assertEquals("Choose three session lengths from 1 to 120 minutes", base.copy(sessionMinutes = sessions).validationError())
        assertNull(base.copy(sessionMinutes = listOf(1, 120, 1), dayReset = "23:59").validationError())
        for (time in listOf("24:00", "4:00", "12:60", "bad")) assertEquals("Reset times must use HH:mm", base.copy(dayReset = time).validationError())
        for (label in listOf("", " ", "a".repeat(61))) for (changed in listOf(base.copy(creditName = label), base.copy(unlockLabel = label), base.copy(youtubeUnlockLabel = label), base.copy(emergencyUnlockLabel = label)))
            assertEquals("Labels must contain between 1 and 60 characters", changed.validationError())
        assertNull(base.copy(creditName = "a".repeat(60)).validationError())
    }

    @Test fun tasksRequireCompatibleEvidenceAndAchievableCorrections() {
        val video = STARTER_TASKS.first()
        val gps = STARTER_TASKS.last()
        val cases = listOf(
            video.copy(id = "1bad") to "Invalid task ID",
            video.copy(id = "a".repeat(65)) to "Invalid task ID",
            video.copy(name = " ") to "Task name",
            video.copy(name = "a".repeat(81)) to "Task name",
            video.copy(verifier = "custom") to "Choose video",
            video.copy(metric = "custom") to "Unsupported measurement",
            gps.copy(metric = "seconds") to "GPS tasks",
            gps.copy(gpsMode = null) to "GPS tasks",
            video.copy(metric = "meters") to "Video tasks",
            video.copy(gpsMode = "walk") to "Video tasks",
            video.copy(unitsPerCredit = 0) to "Units per credit",
            video.copy(unitsPerCredit = 100001) to "Units per credit",
            video.copy(correctionMinimum = 0) to "Correction minimum",
            video.copy(correctionMinimum = 100001) to "Correction minimum",
            video.copy(correctionMinimum = 151) to "Video repetition",
            video.copy(metric = "seconds", correctionMinimum = 31) to "A video correction",
            video.copy(instructions = " ") to "Task needs",
            video.copy(instructions = "a".repeat(2001)) to "Task needs",
        )
        for ((task, message) in cases) assertTrue("$task", task.validationError()!!.startsWith(message))
        for (task in listOf(video.copy(correctionMinimum = 150), video.copy(metric = "seconds", correctionMinimum = 30),
            gps.copy(correctionMinimum = 100000, unitsPerCredit = 100000), gps.copy(gpsMode = "sprint"),
            video.copy(id = "a".repeat(64), name = "n".repeat(80), instructions = "i".repeat(2000)))) assertNull(task.validationError())
        assertEquals("Configuration revision cannot be negative", AppConfiguration(revision = -1).validationError())
        assertEquals("Task IDs must be unique", AppConfiguration(tasks = listOf(video, video)).validationError())
        assertEquals("At most 50 tasks are supported", AppConfiguration(tasks = List(51) { video.copy(id = "task$it") }).validationError())
        assertNull(AppConfiguration(tasks = List(50) { video.copy(id = "task$it") }).validationError())
    }

    @Test fun preciseMeasurementsAcceptBoundaryValuesAndValidOptionalCorrections() {
        val task = STARTER_TASKS.first().copy(metric = "seconds")
        val base = TaskMeasurementReceipt("r", task.id, 0, task, 0, 0, 0)
        assertNull(base.validationError(0))
        assertNull(base.copy(value = 1_000_000, measuredUnitsText = "1000000", minutesPerCredit = 120,
            correctedUnits = 100000, correctedUnitsText = "100000", maximumCorrectableUnits = 30.5).validationError(0))
        assertNull(base.copy(measuredUnitsText = "1e-128", maximumCorrectableUnits = 0.0).validationError(0))
        val gps = STARTER_TASKS.last()
        assertNull(base.copy(taskId = gps.id, task = gps, manualCompletion = true, fallbackEligible = true).validationError(0))
    }
}
