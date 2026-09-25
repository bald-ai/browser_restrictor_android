package com.michalkrsik.internetrestrictor.data

import androidx.datastore.core.DataStore
import com.michalkrsik.internetrestrictor.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import java.io.IOException

class RepositoryEnforcementTest {
    private val time = FakeTimeSource(1_790_250_000_000L, 1000)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val store = Store()
    private val repository = RestrictorRepository(timeSource = time, applicationScope = scope, dataStoreOverride = store)
    private class Store : DataStore<PersistedState> {
        override val data = MutableStateFlow(PersistedState())
        var fail = false
        override suspend fun updateData(transform: suspend (PersistedState) -> PersistedState): PersistedState {
            if (fail) throw IOException("fixture disk full")
            return transform(data.value).also { data.value = it }
        }
    }
    @After fun close() { scope.cancel() }
    private suspend fun connect(budget: Long = 0) {
        repository.awaitLoaded()
        assertTrue(repository.applyConfiguration(AppConfiguration(revision = 1, settings = ConfigurationSettings(dailyBudgetMinutes = budget, waitSeconds = 2))))
        repository.updateDeviceAccess(true, true, true, false)
        repository.updateServiceRuntime(ForegroundClass.BROWSER, ProtectedTarget.BROWSER_POOL, gateFocused = true, serviceConnected = true)
    }
    private suspend fun tick(ms: Long) { time.advance(ms); repository.tick(time.elapsedMs, time.wallMs) }

    @Test fun retryRejectsStaleOrMissingGateTokensAndReplacesOnlyAValidLatch() = runBlocking {
        connect()
        val gate = repository.snapshot.value
        assertEquals(GateMode.OUT_OF_TIME, gate.canonicalMode)
        assertNotNull(gate.gateSessionId); assertNotNull(gate.outOfTimeLatchToken)
        for ((revision, session, latch) in listOf(
            Triple(gate.stateRevision - 1, gate.gateSessionId, gate.outOfTimeLatchToken),
            Triple(gate.stateRevision, null, gate.outOfTimeLatchToken),
            Triple(gate.stateRevision, "stale", gate.outOfTimeLatchToken),
            Triple(gate.stateRevision, gate.gateSessionId, null),
            Triple(gate.stateRevision, gate.gateSessionId, "stale"),
        )) {
            assertFalse(repository.tryAgain(revision, session, latch))
            assertEquals(gate.gateSessionId, repository.snapshot.value.gateSessionId)
        }
        assertTrue(repository.tryAgain(gate.stateRevision, gate.gateSessionId, gate.outOfTimeLatchToken))
        assertEquals(gate.stateRevision + 1, repository.snapshot.value.stateRevision)
        assertNotEquals(gate.outOfTimeLatchToken, repository.snapshot.value.outOfTimeLatchToken)
        assertNull(repository.snapshot.value.lastErrorMessage)
        repository.updateServiceRuntime(activeTarget = null)
        val background = repository.snapshot.value
        assertFalse(repository.tryAgain(background.stateRevision, null, null))
    }

    @Test fun retryWriteFailurePreservesDurableRevisionAndDoesNotUnlockTheBrowser() = runBlocking {
        connect()
        val gate = repository.snapshot.value
        val before = store.data.value
        store.fail = true
        assertFalse(repository.tryAgain(gate.stateRevision, gate.gateSessionId, gate.outOfTimeLatchToken))
        assertEquals(before, store.data.value)
        assertEquals(gate.outOfTimeLatchToken, repository.snapshot.value.outOfTimeLatchToken)
        assertNotNull(repository.snapshot.value.lastErrorMessage)
    }

    @Test fun focusedWaitPausesWithLostFocusAndGrantsAccessOnlyAfterDurableCompletion() = runBlocking {
        connect(10)
        assertTrue(repository.recordPushupRepetitions(20))
        var snapshot = repository.snapshot.value
        assertEquals(GateMode.PAUSED, snapshot.canonicalMode)
        assertTrue(repository.startWait(snapshot.stateRevision, snapshot.settings.sessionMinutes.first()))
        tick(500)
        assertEquals(500, repository.snapshot.value.waitSnapshot!!.accumulatedFocusMs)
        repository.updateServiceRuntime(gateFocused = false)
        tick(1000)
        assertEquals(500, repository.snapshot.value.waitSnapshot!!.accumulatedFocusMs)
        repository.updateServiceRuntime(gateFocused = true)
        store.fail = true
        tick(1500)
        assertEquals(500, store.data.value.pendingWait!!.accumulatedFocusMs)
        assertNull(store.data.value.activeUnlock)
        store.fail = false
        tick(1500)
        snapshot = repository.snapshot.value
        assertNull(snapshot.waitSnapshot)
        assertEquals(UnlockSource.NORMAL, snapshot.activeUnlock!!.source)
        assertEquals(GateMode.ACCESS_GRANTED_NORMAL, snapshot.canonicalMode)
        assertTrue(repository.cancelActiveUnlock())
        assertNull(repository.snapshot.value.activeUnlock)
    }

    @Test fun youtubeGateHasIndependentSessionAndNeverRetainsABrowserExhaustionLatch() = runBlocking {
        connect()
        val browserGate = repository.snapshot.value
        repository.updateServiceRuntime(ForegroundClass.OTHER, ProtectedTarget.YOUTUBE)
        val youtube = repository.snapshot.value
        assertEquals(GateMode.YOUTUBE_LOCKED, youtube.canonicalMode)
        assertNull(youtube.outOfTimeLatchToken)
        assertNotNull(youtube.gateSessionId)
        assertTrue(repository.recordPushupRepetitions(20))
        assertEquals(GateMode.YOUTUBE_ACCESS_GRANTED, repository.snapshot.value.canonicalMode)
        assertNull(repository.snapshot.value.gateSessionId)
        repository.updateServiceRuntime(ForegroundClass.BROWSER, ProtectedTarget.BROWSER_POOL)
        assertNotEquals(browserGate.outOfTimeLatchToken, repository.snapshot.value.outOfTimeLatchToken)
    }

    @Test fun settingsCommandsCanonicalizeProtectionValidatePresetsAndPersistImmediatePreferences() = runBlocking {
        connect(10)
        val snapshot = repository.snapshot.value
        assertFalse(repository.saveSettings(snapshot.stateRevision - 1, snapshot.settings))
        assertFalse(repository.saveSettings(snapshot.stateRevision, snapshot.settings.copy(sessionMinutes = listOf(0, 3, 5))))
        assertTrue(repository.saveSettings(snapshot.stateRevision, snapshot.settings.copy(enforcementEnabled = false, pauseUntilWallMs = time.wallMs + 10, delayEnabled = false)))
        assertTrue(repository.snapshot.value.settings.enforcementEnabled)
        assertTrue(repository.snapshot.value.settings.delayEnabled)
        assertNull(repository.snapshot.value.settings.pauseUntilWallMs)
        assertTrue(repository.setTheme(ThemePreference.LIGHT)); assertTrue(repository.completeOnboarding())
        assertEquals(ThemePreference.LIGHT, store.data.value.settings.theme)
        assertTrue(store.data.value.settings.onboardingCompleted)
        assertTrue(repository.clearReportHistory())
    }
}
