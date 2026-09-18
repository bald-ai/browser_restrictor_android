package com.michalkrsik.internetrestrictor.v1

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class V1DashboardUiTest {
    @get:Rule val compose = createComposeRule()
    private fun snapshot(): StateSnapshot {
        val now = 1_789_553_600_000L
        val engine = RestrictionEngine()
        val task = TaskDefinition("teeth", "Brush teeth", "video", "seconds", 20, 1, "Measure visible brushing time.")
        val config = AppConfiguration(2, ConfigurationSettings(minutesPerCredit = 7, dayReset = "21:15"), listOf(task, task.copy(id = "paused", name = "Paused task", paused = true)))
        val state = engine.normalize(PersistedState(configuration = config), now, ZoneId.of("UTC"))
        return engine.snapshot(state, RuntimeSignals(storageReady = true), now, ZoneId.of("UTC"))
    }

    @Test fun configurableTaskLaunchesWithItsStableIdAndPausedTasksAreHidden() {
        val snapshot = snapshot()
        var selected: String? = null
        compose.setContent {
            RestrictorTheme(snapshot.settings.theme) {
                V1Dashboard(snapshot, "Setup synced", {}, {}, { selected = it.id }, {}, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithText("Brush teeth").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("teeth", selected) }
        compose.onNodeWithText("Paused task").assertDoesNotExist()
        compose.onNodeWithText("PUSH-UPS").assertDoesNotExist()
        compose.onNodeWithText("20 seconds · +7 min").assertExists()
    }

    @Test fun buttonEntryForCustomizationWorksWithoutVoice() {
        val snapshot = snapshot()
        var opened = 0
        compose.setContent {
            RestrictorTheme(snapshot.settings.theme) {
                V1Dashboard(snapshot, "Setup synced", { opened++ }, {}, {}, {}, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithText("Open Friction assistant").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun manuallyConfirmedGpsCompletionDoesNotOfferNumericCorrection() {
        val base = snapshot()
        val task = STARTER_TASKS.first { it.id == "sprint" }
        val measurement = TaskMeasurementReceipt("gps-fallback", task.id, 2, task, 10,
            base.wallNowMs - 20_000, base.wallNowMs, correctionEligible = true,
            fallbackEligible = true, manualCompletion = true)
        val receipt = SavedTaskReceipt(measurement, value = task.unitsPerCredit,
            dayKey = "test-day", budgetKey = "test-budget", rewardEligible = true,
            awardedCredits = 1, awardedMinutes = 7)
        val snapshot = base.copy(taskReceipts = listOf(receipt), modifiableTaskReceiptIds = setOf(measurement.id))
        compose.setContent {
            RestrictorTheme(snapshot.settings.theme) {
                V1Dashboard(snapshot, "Setup synced", {}, {}, {}, {}, {}, { _, _ -> }, {})
            }
        }
        compose.onNodeWithText("LAST RECORDED RESULT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Correct measurement").assertDoesNotExist()
        compose.onNodeWithText("I completed this task · 1 credit").assertDoesNotExist()
    }

    @Test fun expiredRecordingDoesNotOfferNumericCorrection() {
        val base = snapshot()
        val task = base.configuration.tasks.first()
        val measurement = TaskMeasurementReceipt("expired", task.id, 2, task, 2, base.wallNowMs - 20_000, base.wallNowMs)
        val receipt = SavedTaskReceipt(measurement, dayKey = "old-day", budgetKey = "expired-period", rewardEligible = true)
        val snapshot = base.copy(taskReceipts = listOf(receipt), modifiableTaskReceiptIds = emptySet())
        compose.setContent { RestrictorTheme(snapshot.settings.theme) { V1Dashboard(snapshot, "Setup synced", {}, {}, {}, {}, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("LAST RECORDED RESULT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Correct measurement").assertDoesNotExist()
    }

    @Test fun correctionEditorSendsTheDecimalWithoutTruncating() {
        val base = snapshot()
        val task = base.configuration.tasks.first()
        val measurement = TaskMeasurementReceipt("decimal", task.id, 2, task, 1, base.wallNowMs - 20_000, base.wallNowMs, measuredUnitsText = "1.7")
        val receipt = SavedTaskReceipt(measurement, dayKey = "day", budgetKey = "current", rewardEligible = true, valueText = "1.7")
        val snapshot = base.copy(taskReceipts = listOf(receipt), modifiableTaskReceiptIds = setOf(measurement.id))
        var saved: Double? = null
        compose.setContent { RestrictorTheme(snapshot.settings.theme) { V1Dashboard(snapshot, "Setup synced", {}, {}, {}, {}, {}, { _, value -> saved = value }, {}) } }
        compose.onNodeWithText("Correct measurement").performScrollTo().performClick()
        compose.onNodeWithText("Save correction").performClick()
        compose.runOnIdle { assertEquals(1.7, saved!!, 0.0) }
    }
}
