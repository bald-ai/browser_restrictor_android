package com.michalkrsik.internetrestrictor.v1

import androidx.test.platform.app.InstrumentationRegistry
import com.michalkrsik.internetrestrictor.appContainer
import com.michalkrsik.internetrestrictor.domain.TaskDefinition
import com.michalkrsik.internetrestrictor.domain.TaskMeasurementReceipt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.TimeZone
import java.util.UUID

/** Opt-in physical gate fixture. Never runs against the daily-use package. */
class V1DeviceIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun prepareGateTestCredit() = runBlocking {
        assumeTrue("Explicit gate fixture opt-in required", InstrumentationRegistry.getArguments().getString("v1GateFixture") == "true")

        assertEquals("com.michalkrsik.browserrestrictor.v1dev", context.packageName)
        val container = context.appContainer
        container.repository.awaitLoaded()
        assertTrue("Separate hosted setup must sync", container.v1.sync())
        val config = container.repository.snapshot.value.configuration
        val before = container.repository.snapshot.value.earnedBudgetRoundsToday
        val now = System.currentTimeMillis()
        val task = TaskDefinition("device_test", "Device test credit", "video", "repetitions", 1, 1,
            "Instrumentation fixture for gate timing; not a real activity verification.")
        val receipt = TaskMeasurementReceipt(
            id = "device-test-${UUID.randomUUID()}", taskId = task.id,
            configRevision = config.revision, task = task, value = 1,
            startedAtWallMs = now - 1000, finishedAtWallMs = now,
            minutesPerCredit = config.settings.minutesPerCredit,
            dayReset = config.settings.dayReset,
            timeZone = TimeZone.getDefault().id,
        )
        assertTrue(container.repository.applyTaskReceipt(receipt))
        assertEquals(before + 1, container.repository.snapshot.value.earnedBudgetRoundsToday)
        assertTrue("Repeat receipt is a no-op", container.repository.applyTaskReceipt(receipt))
        assertEquals(before + 1, container.repository.snapshot.value.earnedBudgetRoundsToday)
        assertTrue(context.getSharedPreferences("v1-device-test", 0).edit()
            .putString("receipt", receipt.id).commit())
        assertTrue(container.v1.sync())
    }

    @Test fun removeGateTestCredit() = runBlocking {
        assumeTrue("Explicit gate fixture opt-in required", InstrumentationRegistry.getArguments().getString("v1GateFixture") == "true")

        assertEquals("com.michalkrsik.browserrestrictor.v1dev", context.packageName)
        val preferences = context.getSharedPreferences("v1-device-test", 0)
        val receipt = preferences.getString("receipt", null) ?: return@runBlocking
        val container = context.appContainer
        container.repository.awaitLoaded()
        val saved = container.repository.snapshot.value.taskReceipts.find { it.measurement.id == receipt }
        if (saved != null && saved.rewardEligible) {
            assertTrue("Remove only this test receipt's reward", container.repository.correctTaskReceipt(receipt, 0))
        }
        assertTrue(preferences.edit().remove("receipt").commit())
        container.v1.sync()
    }
}
