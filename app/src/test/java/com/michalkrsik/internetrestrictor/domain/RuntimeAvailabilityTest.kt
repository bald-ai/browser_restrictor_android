package com.michalkrsik.internetrestrictor.domain

import org.junit.Test
import org.junit.Assert.assertEquals
import java.time.ZoneId

class RuntimeAvailabilityTest {
    @Test fun eachUnavailableRuntimeSignalPreventsAccessEvenWhenBudgetRemains() {
        val engine = RestrictionEngine()
        val now = 1_790_250_000_000L
        val state = engine.normalize(PersistedState(configuration = AppConfiguration(settings = ConfigurationSettings(dailyBudgetMinutes = 10))), now, ZoneId.of("UTC"))
        val healthy = RuntimeSignals(activeTarget = ProtectedTarget.BROWSER_POOL, storageReady = true, accessibilityEnabled = true, serviceConnected = true)
        val cases = listOf(
            healthy to GateMode.PAUSED,
            healthy.copy(storageError = true) to GateMode.STORAGE_ERROR,
            healthy.copy(activeTarget = null) to GateMode.BROWSER_BACKGROUND,
            healthy.copy(screenInteractive = false) to GateMode.BROWSER_BACKGROUND,
            healthy.copy(keyguardUnlocked = false) to GateMode.BROWSER_BACKGROUND,
            healthy.copy(storageReady = false) to GateMode.SERVICE_UNAVAILABLE,
            healthy.copy(accessibilityEnabled = false) to GateMode.SERVICE_UNAVAILABLE,
            healthy.copy(serviceConnected = false) to GateMode.SERVICE_UNAVAILABLE,
        )
        for ((runtime, expected) in cases) assertEquals(runtime.toString(), expected, engine.underlyingMode(state, runtime, now))
    }
}
