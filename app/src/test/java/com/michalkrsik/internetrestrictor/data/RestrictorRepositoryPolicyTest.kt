package com.michalkrsik.internetrestrictor.data

import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.PendingWait
import com.michalkrsik.internetrestrictor.domain.PersistedState
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RestrictorRepositoryPolicyTest {
    @Test
    fun dailyNormalizationFailureIsReportedAndRetriedDurably() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val time = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(now, 1000L, zone)
        val store = FailingRewardStore(PersistedState())
        val repo = RestrictorRepository(timeSource = time,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            dataStoreOverride = store)
        repo.awaitLoaded()
        repo.tick(time.elapsedMs, time.wallMs)
        val before = store.data.first()
        store.fail = true
        time.advance(24 * 60 * 60 * 1000L)
        repo.tick(time.elapsedMs, time.wallMs)
        assertEquals(before, store.data.first())
        assertNotNull(repo.snapshot.value.lastErrorMessage)
        assertTrue(repo.snapshot.value.lastErrorMessage!!.contains("could not be saved"))
        store.fail = false
        repo.tick(time.elapsedMs, time.wallMs)
        assertEquals("2026-07-11", store.data.first().dailyCounter.localDayKey)
        assertNull(repo.snapshot.value.lastErrorMessage)
    }

    @Test
    fun correctionWriteFailureKeepsOriginalAndRetryUpdatesOnce() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val time = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(now, 1000L, zone)
        val store = FailingRewardStore(PersistedState())
        val repo = RestrictorRepository(timeSource = time,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            dataStoreOverride = store)
        repo.awaitLoaded()
        repo.recordVerifiedExercise("positive", com.michalkrsik.internetrestrictor.domain.ExerciseId.PUSH_UP, 1)
        store.fail = true
        assertEquals(com.michalkrsik.internetrestrictor.domain.RewardSaveResult.StorageFailure,
            repo.correctExercise("positive", com.michalkrsik.internetrestrictor.domain.ExerciseId.PUSH_UP, 5))
        assertEquals(1L, repo.snapshot.value.lastExercise!!.value)
        assertEquals(0L, repo.snapshot.value.earnedBudgetRoundsToday)
        store.fail = false
        repeat(2) { repo.correctExercise("positive", com.michalkrsik.internetrestrictor.domain.ExerciseId.PUSH_UP, 5) }
        assertEquals(5L, repo.snapshot.value.lastExercise!!.value)
        assertEquals(1L, repo.snapshot.value.earnedBudgetRoundsToday)
        org.junit.Assert.assertTrue(repo.correctExercise("unknown", com.michalkrsik.internetrestrictor.domain.ExerciseId.PUSH_UP, 5)
            is com.michalkrsik.internetrestrictor.domain.RewardSaveResult.Rejected)
    }

    @Test
    fun correctionHistoryWritesAtomicallyWithTheCorrectedReceipt() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 9, 14, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val time = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(now, 1000L, zone)
        val store = FailingRewardStore(PersistedState())
        val repo = RestrictorRepository(timeSource = time,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            dataStoreOverride = store)
        repo.awaitLoaded()
        repo.recordVerifiedExercise("positive", com.michalkrsik.internetrestrictor.domain.ExerciseId.PUSH_UP, 1)
        store.fail = true
        assertEquals(com.michalkrsik.internetrestrictor.domain.RewardSaveResult.StorageFailure,
            repo.correctExercise("positive", com.michalkrsik.internetrestrictor.domain.ExerciseId.PUSH_UP, 5))
        // A failed write publishes neither the history event nor the correction.
        assertTrue(store.data.first().correctionHistory.isEmpty())
        assertFalse(store.data.first().lastExercise!!.corrected)
        store.fail = false
        // A successful retry records exactly one event.
        assertTrue(repo.correctExercise("positive", com.michalkrsik.internetrestrictor.domain.ExerciseId.PUSH_UP, 5)
            is com.michalkrsik.internetrestrictor.domain.RewardSaveResult.Saved)
        val event = store.data.first().correctionHistory.single()
        assertEquals("positive", event.receiptId)
        assertEquals(now, event.correctedAtWallMs)
        assertEquals("push_up", event.originalType)
        assertEquals(1L, event.originalValue)
        assertEquals("push_up", event.correctedType)
        assertEquals(5L, event.correctedValue)
        // Re-saving the same value is a no-op for history.
        repo.correctExercise("positive", com.michalkrsik.internetrestrictor.domain.ExerciseId.PUSH_UP, 5)
        assertEquals(1, store.data.first().correctionHistory.size)
        // History survives a restart: a new repository on the same store loads and appends.
        val reloaded = RestrictorRepository(timeSource = time,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            dataStoreOverride = store)
        reloaded.awaitLoaded()
        assertTrue(reloaded.correctExercise("positive", com.michalkrsik.internetrestrictor.domain.ExerciseId.PLANK, 7)
            is com.michalkrsik.internetrestrictor.domain.RewardSaveResult.Saved)
        val persisted = store.data.first()
        assertEquals(2, persisted.correctionHistory.size)
        assertEquals("plank", persisted.correctionHistory[1].correctedType)
        assertEquals(7L, persisted.correctionHistory[1].correctedValue)
    }

    @Test
    fun clockJumpCannotInflateBufferedUsage() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val time = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(now, 1_000L, zone)
        val engine = com.michalkrsik.internetrestrictor.domain.RestrictionEngine()
        val earned = (engine.recordSprintRecording(PersistedState(), "budget", 600L, false, now, zone)
            as com.michalkrsik.internetrestrictor.domain.CommandResult.Applied).state
        val initial = earned.copy(activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
            "normal", com.michalkrsik.internetrestrictor.domain.UnlockSource.NORMAL, now, now + 600_000L))
        val repo = RestrictorRepository(timeSource = time,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            dataStoreOverride = FailingRewardStore(initial))
        repo.awaitLoaded()
        repo.updateDeviceAccess(true, true, true, true)
        repo.updateServiceRuntime(foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true, keyguardUnlocked = true, serviceConnected = true)
        time.advance(250L)
        repo.tick(time.elapsedMs, time.wallMs)
        time.advance(250L)
        time.wallMs += 60_000L
        repo.tick(time.elapsedMs, time.wallMs)
        time.advance(250L)
        repo.flushAccounting()
        assertEquals(750L, repo.snapshot.value.todayUsageReport.protectedUsageMs)
        assertEquals(750L, repo.snapshot.value.budgetSnapshots.single().usedMs)
    }

    @Test
    fun rewardRetryPreservesReceiptAndReturnsAwardAcrossDailyReset() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val before = java.time.ZonedDateTime.of(2026, 7, 10, 3, 59, 59, 0, zone).toInstant().toEpochMilli()
        val time = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(before, 1_000L, zone)
        val engine = com.michalkrsik.internetrestrictor.domain.RestrictionEngine()
        val initial = (engine.recordSprintRecording(PersistedState(), "previous", 600L, false, before, zone)
            as com.michalkrsik.internetrestrictor.domain.CommandResult.Applied).state
        val store = FailingRewardStore(initial)
        val repo = RestrictorRepository(timeSource = time,
            applicationScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            dataStoreOverride = store, diagnosticSink = { throw java.io.IOException("Log disk full") })
        repo.awaitLoaded()
        store.fail = true
        val failed = repo.recordSprintRecording("retry-receipt", 150L)
        assertEquals(com.michalkrsik.internetrestrictor.domain.RewardSaveResult.StorageFailure, failed)
        assertEquals(5L, repo.snapshot.value.earnedBudgetRoundsToday)
        store.fail = false
        time.advance(2_000L)
        val saved = repo.recordSprintRecording("retry-receipt", 150L)
            as com.michalkrsik.internetrestrictor.domain.RewardSaveResult.Saved
        assertEquals(1L, saved.commit.awardedSets)
        assertEquals(30L, saved.commit.remainder)
        assertEquals("2026-07-10", saved.commit.dayKey)
        org.junit.Assert.assertTrue(repo.recordSprintRecording("retry-receipt", 150L)
            is com.michalkrsik.internetrestrictor.domain.RewardSaveResult.Rejected)
        assertEquals(1L, repo.snapshot.value.earnedBudgetRoundsToday)
        store.fail = true
        assertEquals(com.michalkrsik.internetrestrictor.domain.RewardSaveResult.StorageFailure,
            repo.recordVerifiedExercise("video-retry", com.michalkrsik.internetrestrictor.domain.ExerciseId.PUSH_UP, 7))
        store.fail = false
        val video = repo.recordVerifiedExercise("video-retry", com.michalkrsik.internetrestrictor.domain.ExerciseId.PUSH_UP, 7)
            as com.michalkrsik.internetrestrictor.domain.RewardSaveResult.Saved
        assertEquals(1L, video.commit.awardedSets)
        assertEquals(12L, video.commit.remainder)
    }

    @Test
    fun disconnectPreservesLastConfirmedBrowserForeground() {
        assertEquals(
            ForegroundClass.BROWSER,
            persistedForegroundAfterServiceUpdate(
                previous = ForegroundClass.BROWSER,
                observed = ForegroundClass.OTHER,
                serviceConnected = false,
            ),
        )
    }

    @Test
    fun connectedObservationReplacesPersistedForeground() {
        assertEquals(
            ForegroundClass.OTHER,
            persistedForegroundAfterServiceUpdate(
                previous = ForegroundClass.BROWSER,
                observed = ForegroundClass.OTHER,
                serviceConnected = true,
            ),
        )
    }

    @Test
    fun leavingBrowserAbortsPendingWait() {
        val state = PersistedState(
            pendingWait = PendingWait(
                id = "wait",
                selectedDurationMs = 10_000,
                requiredFocusMs = 5_000,
                accumulatedFocusMs = 2_000,
                createdAtWallMs = 1_000,
                updatedAtWallMs = 3_000,
            ),
        )

        val updated = abortPendingWaitAfterServiceUpdate(state, browserActive = false)

        assertNull(updated.pendingWait)
    }

    @Test
    fun pendingWaitRemainsWhileBrowserFlowIsActive() {
        val state = PersistedState(
            pendingWait = PendingWait("wait", 10_000, 5_000, 2_000, 1_000, 3_000),
        )

        val updated = abortPendingWaitAfterServiceUpdate(state, browserActive = true)

        assertNotNull(updated.pendingWait)
    }

    @Test
    fun bufferedNormalUsageIsFlushedAndChargedBeforeEmergencyActivation() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val timeSource = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(wallMs = now, elapsedMs = 1_000_000L, zone = zone)
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()

        val initial = PersistedState(
            dailyCounter = com.michalkrsik.internetrestrictor.domain.DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 5,
                spendableBudgetRounds = 5,
            ),
            activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
                id = "normal-unlock",
                source = com.michalkrsik.internetrestrictor.domain.UnlockSource.NORMAL,
                grantedAtWallMs = now,
                expiresAtWallMs = now + 30_000L,
            ),
        )

        val repository = RestrictorRepository(
            timeSource = timeSource,
            applicationScope = testScope,
            diagnosticSink = { diagnostics += it },
            dataStoreOverride = InMemoryDataStore(initial),
        )
        repository.awaitLoaded()
        repository.updateDeviceAccess(
            accessibilityEnabled = true,
            usageAccessGranted = true,
            supportedBrowserInstalled = true,
            advancedProtectionEnabled = true,
        )
        repository.updateServiceRuntime(
            serviceConnected = true,
            foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true,
            keyguardUnlocked = true,
        )

        // Initialize tick anchors
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // Browse for 30 seconds until normal unlock reaches expiry
        repeat(30) {
            timeSource.advance(1_000L)
            repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
        }

        // Gate is now paused, eligible for emergency skip
        val snapshot = repository.snapshot.value
        assertEquals(com.michalkrsik.internetrestrictor.domain.GateMode.PAUSED, snapshot.canonicalMode)
        org.junit.Assert.assertTrue(snapshot.skipSnapshot.eligible)

        // Activate emergency skip
        val activated = repository.emergencySkip(snapshot.stateRevision, snapshot.pendingWaitId)
        org.junit.Assert.assertTrue(activated)

        // Earlier normal usage was charged, NOT free
        val afterSkip = repository.snapshot.value
        assertEquals(30_000L, afterSkip.budgetSnapshots.single().usedMs)
        assertEquals(com.michalkrsik.internetrestrictor.domain.UnlockSource.EMERGENCY, afterSkip.activeUnlock?.source)
        org.junit.Assert.assertTrue(diagnostics.any { it.contains("Emergency access granted") })
    }

    @Test
    fun pendingEmergencyUsageIsFlushedFreeBeforeExpiry() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val timeSource = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(wallMs = now, elapsedMs = 1_000_000L, zone = zone)
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()

        val expiry = now + 15 * com.michalkrsik.internetrestrictor.domain.MINUTE_MS
        val initial = PersistedState(
            dailyCounter = com.michalkrsik.internetrestrictor.domain.DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 5,
                spendableBudgetRounds = 5,
            ),
            activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
                id = "emergency-unlock",
                source = com.michalkrsik.internetrestrictor.domain.UnlockSource.EMERGENCY,
                grantedAtWallMs = now,
                expiresAtWallMs = expiry,
            ),
        )

        val repository = RestrictorRepository(
            timeSource = timeSource,
            applicationScope = testScope,
            diagnosticSink = { diagnostics += it },
            dataStoreOverride = InMemoryDataStore(initial),
        )
        repository.awaitLoaded()
        repository.updateDeviceAccess(
            accessibilityEnabled = true,
            usageAccessGranted = true,
            supportedBrowserInstalled = true,
            advancedProtectionEnabled = true,
        )
        repository.updateServiceRuntime(
            serviceConnected = true,
            foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true,
            keyguardUnlocked = true,
        )

        // Initialize tick anchors
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // Browse for 15 minutes (900 seconds) in 1-second ticks
        repeat(900) {
            timeSource.advance(1_000L)
            repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
        }

        // Advance 10 more seconds past expiry
        repeat(10) {
            timeSource.advance(1_000L)
            repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
        }

        val snapshot = repository.snapshot.value
        // Emergency browsing consumed zero earned budget
        assertEquals(0L, snapshot.budgetSnapshots.single().usedMs)
        // Exactly 15 minutes was recorded in report history
        assertEquals(15 * com.michalkrsik.internetrestrictor.domain.MINUTE_MS, snapshot.todayUsageReport.protectedUsageMs)
        // Unlock has expired
        assertNull(snapshot.activeUnlock)
        // Expiry diagnostic logged
        org.junit.Assert.assertTrue(diagnostics.any { it.contains("Emergency access expired") })
        val lastSummary = diagnostics.filter { it.contains("Usage committed:") }.last()
        org.junit.Assert.assertTrue(lastSummary.contains("(emergency active)"))
    }

    @Test
    fun emergencyAccessWithExhaustedBudgetThrottlesDiagnosticsAndPreservesContext() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val timeSource = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(wallMs = now, elapsedMs = 1_000_000L, zone = zone)
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()

        val expiry = now + 15 * com.michalkrsik.internetrestrictor.domain.MINUTE_MS
        val initial = PersistedState(
            dailyCounter = com.michalkrsik.internetrestrictor.domain.DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 5,
                spendableBudgetRounds = 0,
            ),
            activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
                id = "emergency-unlock",
                source = com.michalkrsik.internetrestrictor.domain.UnlockSource.EMERGENCY,
                grantedAtWallMs = now,
                expiresAtWallMs = expiry,
            ),
        )

        val repository = RestrictorRepository(
            timeSource = timeSource,
            applicationScope = testScope,
            diagnosticSink = { diagnostics += it },
            dataStoreOverride = InMemoryDataStore(initial),
        )
        repository.awaitLoaded()
        repository.updateDeviceAccess(
            accessibilityEnabled = true,
            usageAccessGranted = true,
            supportedBrowserInstalled = true,
            advancedProtectionEnabled = true,
        )
        repository.updateServiceRuntime(
            serviceConnected = true,
            foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true,
            keyguardUnlocked = true,
        )

        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // Browse for 15 minutes (900 seconds) in 1-second ticks
        repeat(900) {
            timeSource.advance(1_000L)
            repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
        }

        // Summary messages must be throttled to roughly 1 per minute (15 total), NOT 900 separate lines
        val summaryMessages = diagnostics.filter { it.contains("Usage committed:") }
        assertEquals(15, summaryMessages.size)

        // All summaries must carry the (emergency active) tag and charged=0ms
        org.junit.Assert.assertTrue(summaryMessages.all { it.contains("(emergency active)") && it.contains("charged=0ms") })

        // Expiry tick
        timeSource.advance(1_000L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        val snapshot = repository.snapshot.value
        assertEquals(0L, snapshot.budgetSnapshots.single().usedMs)
        assertEquals(15 * com.michalkrsik.internetrestrictor.domain.MINUTE_MS, snapshot.todayUsageReport.protectedUsageMs)
        assertNull(snapshot.activeUnlock)
        org.junit.Assert.assertTrue(diagnostics.any { it.contains("Emergency access expired") })
    }

    @Test
    fun emergencyAccessAfterEveningResetWithZeroBudgetThrottlesDiagnostics() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val afterEvening = java.time.ZonedDateTime.of(2026, 7, 10, 20, 30, 0, 0, zone).toInstant().toEpochMilli()
        val timeSource = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(wallMs = afterEvening, elapsedMs = 1_000_000L, zone = zone)
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()

        val expiry = afterEvening + 15 * com.michalkrsik.internetrestrictor.domain.MINUTE_MS
        val initial = PersistedState(
            dailyCounter = com.michalkrsik.internetrestrictor.domain.DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 5,
                spendableBudgetRounds = 0,
                legacyEveningResetApplied = true,
            ),
            activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
                id = "emergency-unlock",
                source = com.michalkrsik.internetrestrictor.domain.UnlockSource.EMERGENCY,
                grantedAtWallMs = afterEvening,
                expiresAtWallMs = expiry,
            ),
        )

        val repository = RestrictorRepository(
            timeSource = timeSource,
            applicationScope = testScope,
            diagnosticSink = { diagnostics += it },
            dataStoreOverride = InMemoryDataStore(initial),
        )
        repository.awaitLoaded()
        repository.updateDeviceAccess(
            accessibilityEnabled = true,
            usageAccessGranted = true,
            supportedBrowserInstalled = true,
            advancedProtectionEnabled = true,
        )
        repository.updateServiceRuntime(
            serviceConnected = true,
            foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true,
            keyguardUnlocked = true,
        )

        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        repeat(900) {
            timeSource.advance(1_000L)
            repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
        }

        val summaryMessages = diagnostics.filter { it.contains("Usage committed:") }
        assertEquals(15, summaryMessages.size)
        org.junit.Assert.assertTrue(summaryMessages.all { it.contains("(emergency active)") && it.contains("charged=0ms") })

        timeSource.advance(1_000L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        val snapshot = repository.snapshot.value
        assertEquals(0L, snapshot.budgetSnapshots.single().usedMs)
        assertEquals(15 * com.michalkrsik.internetrestrictor.domain.MINUTE_MS, snapshot.todayUsageReport.protectedUsageMs)
        assertNull(snapshot.activeUnlock)
        org.junit.Assert.assertTrue(diagnostics.any { it.contains("Emergency access expired") })
    }

    @Test
    fun normalSubHeartbeatBufferedUsageIsCommittedBeforeCancelAndEmergencyActivated() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val timeSource = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(wallMs = now, elapsedMs = 1_000_000L, zone = zone)
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()

        val initial = PersistedState(
            dailyCounter = com.michalkrsik.internetrestrictor.domain.DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 5,
                spendableBudgetRounds = 5,
            ),
            dailyUsage = com.michalkrsik.internetrestrictor.domain.UsagePeriodState(
                period = com.michalkrsik.internetrestrictor.domain.BudgetPeriod.DAILY,
                key = "2026-07-10",
                usedMs = 0L,
            ),
            activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
                id = "normal-unlock",
                source = com.michalkrsik.internetrestrictor.domain.UnlockSource.NORMAL,
                grantedAtWallMs = now,
                expiresAtWallMs = now + 60_000L,
            ),
        )

        val repository = RestrictorRepository(
            timeSource = timeSource,
            applicationScope = testScope,
            diagnosticSink = { diagnostics += it },
            dataStoreOverride = InMemoryDataStore(initial),
        )
        repository.awaitLoaded()
        repository.updateDeviceAccess(
            accessibilityEnabled = true,
            usageAccessGranted = true,
            supportedBrowserInstalled = true,
            advancedProtectionEnabled = true,
        )
        repository.updateServiceRuntime(
            serviceConnected = true,
            foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true,
            keyguardUnlocked = true,
        )

        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // 1. Advance 600ms (sub-heartbeat tick, buffer holds 600ms uncommitted)
        timeSource.advance(600L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
        assertEquals(0L, repository.snapshot.value.budgetSnapshots.single().usedMs)
        assertEquals(0L, repository.snapshot.value.todayUsageReport.protectedUsageMs)

        // 2. Cancel active unlock (valid transition): must commit 600ms before cancellation
        val cancelled = repository.cancelActiveUnlock()
        org.junit.Assert.assertTrue(cancelled)

        val postCancel = repository.snapshot.value
        assertEquals(600L, postCancel.budgetSnapshots.single().usedMs)
        assertEquals(600L, postCancel.todayUsageReport.protectedUsageMs)
        assertNull(postCancel.activeUnlock)
        org.junit.Assert.assertTrue(postCancel.skipSnapshot.eligible)

        // 3. Activate emergency access
        val activated = repository.emergencySkip(postCancel.stateRevision, postCancel.pendingWaitId)
        org.junit.Assert.assertTrue(activated)

        val emergencySnapshot = repository.snapshot.value
        assertEquals(com.michalkrsik.internetrestrictor.domain.UnlockSource.EMERGENCY, emergencySnapshot.activeUnlock?.source)
        assertEquals(600L, emergencySnapshot.budgetSnapshots.single().usedMs)
    }

    @Test
    fun emergencySubHeartbeatBufferedUsageIsFlushedFreeAtExpiry() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val timeSource = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(wallMs = now, elapsedMs = 1_000_000L, zone = zone)
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()

        val expiry = now + 900L
        val initial = PersistedState(
            dailyCounter = com.michalkrsik.internetrestrictor.domain.DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 5,
                spendableBudgetRounds = 5,
            ),
            dailyUsage = com.michalkrsik.internetrestrictor.domain.UsagePeriodState(
                period = com.michalkrsik.internetrestrictor.domain.BudgetPeriod.DAILY,
                key = "2026-07-10",
                usedMs = 0L,
            ),
            activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
                id = "emergency-unlock",
                source = com.michalkrsik.internetrestrictor.domain.UnlockSource.EMERGENCY,
                grantedAtWallMs = now,
                expiresAtWallMs = expiry,
            ),
        )

        val repository = RestrictorRepository(
            timeSource = timeSource,
            applicationScope = testScope,
            diagnosticSink = { diagnostics += it },
            dataStoreOverride = InMemoryDataStore(initial),
        )
        repository.awaitLoaded()
        repository.updateDeviceAccess(
            accessibilityEnabled = true,
            usageAccessGranted = true,
            supportedBrowserInstalled = true,
            advancedProtectionEnabled = true,
        )
        repository.updateServiceRuntime(
            serviceConnected = true,
            foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true,
            keyguardUnlocked = true,
        )

        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // 1. Tick 600ms (< 1000ms heartbeat): buffered, not yet flushed
        timeSource.advance(600L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
        assertEquals(0L, repository.snapshot.value.todayUsageReport.protectedUsageMs)

        // 2. Tick 300ms (reaches expiry at 900ms, total 900ms < 1000ms heartbeat)
        timeSource.advance(300L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        val snapshot = repository.snapshot.value
        assertNull(snapshot.activeUnlock)
        assertEquals(com.michalkrsik.internetrestrictor.domain.GateMode.PAUSED, snapshot.canonicalMode)
        // 900ms reported free: 900ms recorded, 0ms charged
        assertEquals(900L, snapshot.todayUsageReport.protectedUsageMs)
        assertEquals(0L, snapshot.budgetSnapshots.single().usedMs)
        org.junit.Assert.assertTrue(diagnostics.any { it.contains("Emergency access expired") })
        val summary = diagnostics.filter { it.contains("Usage committed:") }.last()
        org.junit.Assert.assertTrue(summary.contains("recorded=900ms, charged=0ms"))
    }

    @Test
    fun commandForcesFlushOfPendingSubHeartbeatUsageBeforeExerciseCredit() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val timeSource = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(wallMs = now, elapsedMs = 1_000_000L, zone = zone)
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()

        val initial = PersistedState(
            dailyCounter = com.michalkrsik.internetrestrictor.domain.DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 1,
                spendableBudgetRounds = 1,
            ),
            dailyUsage = com.michalkrsik.internetrestrictor.domain.UsagePeriodState(
                period = com.michalkrsik.internetrestrictor.domain.BudgetPeriod.DAILY,
                key = "2026-07-10",
                usedMs = 0L,
            ),
            activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
                id = "normal-unlock",
                source = com.michalkrsik.internetrestrictor.domain.UnlockSource.NORMAL,
                grantedAtWallMs = now,
                expiresAtWallMs = now + 60_000L,
            ),
        )

        val repository = RestrictorRepository(
            timeSource = timeSource,
            applicationScope = testScope,
            diagnosticSink = { diagnostics += it },
            dataStoreOverride = InMemoryDataStore(initial),
        )
        repository.awaitLoaded()
        repository.updateDeviceAccess(
            accessibilityEnabled = true,
            usageAccessGranted = true,
            supportedBrowserInstalled = true,
            advancedProtectionEnabled = true,
        )
        repository.updateServiceRuntime(
            serviceConnected = true,
            foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true,
            keyguardUnlocked = true,
        )

        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // Advance 600ms: sub-heartbeat tick leaves 600ms pending in buffer
        timeSource.advance(600L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
        assertEquals(0L, repository.snapshot.value.budgetSnapshots.single().usedMs)
        assertEquals(0L, repository.snapshot.value.todayUsageReport.protectedUsageMs)
        assertEquals(1L, repository.snapshot.value.earnedBudgetRoundsToday)

        // Valid mid-window exercise-credit command (5 pushups = 1 set)
        val credited = repository.recordPushupRepetitions(5)
        org.junit.Assert.assertTrue(credited)

        val postCredit = repository.snapshot.value
        assertEquals(2L, postCredit.earnedBudgetRoundsToday)
        assertEquals(600L, postCredit.budgetSnapshots.single().usedMs)
        assertEquals(600L, postCredit.todayUsageReport.protectedUsageMs)
        val summary = diagnostics.filter { it.contains("Usage committed:") }.last()
        org.junit.Assert.assertTrue(summary.contains("recorded=600ms, charged=600ms"))
    }

    @Test
    fun normalExhaustionMidBufferTruncatesRecordedAndChargedAccurately() = kotlinx.coroutines.runBlocking {
        for (tickDuration in listOf(250L, 1000L)) {
        val zone = java.time.ZoneId.of("UTC")
        val now = java.time.ZonedDateTime.of(2026, 7, 10, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val timeSource = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(wallMs = now, elapsedMs = 1_000_000L, zone = zone)
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()

        // 1 set = 3 minutes (180,000ms). User has 179,800ms used => exactly 200ms remaining!
        val initial = PersistedState(
            dailyCounter = com.michalkrsik.internetrestrictor.domain.DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 1,
                spendableBudgetRounds = 1,
            ),
            dailyUsage = com.michalkrsik.internetrestrictor.domain.UsagePeriodState(
                period = com.michalkrsik.internetrestrictor.domain.BudgetPeriod.DAILY,
                key = "2026-07-10",
                usedMs = 3 * com.michalkrsik.internetrestrictor.domain.MINUTE_MS - 200L,
            ),
            activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
                id = "normal-unlock",
                source = com.michalkrsik.internetrestrictor.domain.UnlockSource.NORMAL,
                grantedAtWallMs = now,
                expiresAtWallMs = now + 10_000L,
            ),
        )

        val repository = RestrictorRepository(
            timeSource = timeSource,
            applicationScope = testScope,
            diagnosticSink = { diagnostics += it },
            dataStoreOverride = InMemoryDataStore(initial),
        )
        repository.awaitLoaded()
        repository.updateDeviceAccess(
            accessibilityEnabled = true,
            usageAccessGranted = true,
            supportedBrowserInstalled = true,
            advancedProtectionEnabled = true,
        )
        repository.updateServiceRuntime(
            serviceConnected = true,
            foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true,
            keyguardUnlocked = true,
        )

        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // Both below-heartbeat and heartbeat ticks must stop at 200ms remaining.
        timeSource.advance(tickDuration)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        val snapshot = repository.snapshot.value
        // Exactly 200ms was charged (exhausting the 3m budget)
        assertEquals(3 * com.michalkrsik.internetrestrictor.domain.MINUTE_MS, snapshot.budgetSnapshots.single().usedMs)
        assertEquals(0L, snapshot.budgetSnapshots.single().remainingMs)
        // Exactly 200ms was recorded in reports
        assertEquals(200L, snapshot.todayUsageReport.protectedUsageMs)
        // Diagnostic recorded=200ms, charged=200ms (NOT 1000ms!)
        val summary = diagnostics.filter { it.contains("Usage committed:") }.last()
        org.junit.Assert.assertTrue(summary.contains("recorded=200ms, charged=200ms"))
        org.junit.Assert.assertTrue(diagnostics.any { it.contains("Browser budget exhausted") })
        }
    }

    @Test
    fun normalChargingUpToResetBoundaryPreservesChargedDiagnosticsAcrossReset() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val resetWallMs = java.time.ZonedDateTime.of(2026, 7, 11, 4, 0, 0, 0, zone).toInstant().toEpochMilli()
        val startWallMs = resetWallMs - 1_000L
        val timeSource = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(wallMs = startWallMs, elapsedMs = 1_000_000L, zone = zone)
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()

        val initial = PersistedState(
            dailyCounter = com.michalkrsik.internetrestrictor.domain.DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 5,
                spendableBudgetRounds = 5,
            ),
            dailyUsage = com.michalkrsik.internetrestrictor.domain.UsagePeriodState(
                period = com.michalkrsik.internetrestrictor.domain.BudgetPeriod.DAILY,
                key = "2026-07-10",
                usedMs = 60_000L,
            ),
            activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
                id = "normal-unlock",
                source = com.michalkrsik.internetrestrictor.domain.UnlockSource.NORMAL,
                grantedAtWallMs = startWallMs,
                expiresAtWallMs = startWallMs + 60_000L,
            ),
        )

        val repository = RestrictorRepository(
            timeSource = timeSource,
            applicationScope = testScope,
            diagnosticSink = { diagnostics += it },
            dataStoreOverride = InMemoryDataStore(initial),
        )
        repository.awaitLoaded()
        repository.updateDeviceAccess(
            accessibilityEnabled = true,
            usageAccessGranted = true,
            supportedBrowserInstalled = true,
            advancedProtectionEnabled = true,
        )
        repository.updateServiceRuntime(
            serviceConnected = true,
            foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true,
            keyguardUnlocked = true,
        )

        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // Browse for 1500ms across 04:00 reset boundary in ONE tick
        timeSource.advance(1_500L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        val postReset = repository.snapshot.value
        // Daily reset was applied: spendable rounds reset to 0, usedMs reset to 0
        org.junit.Assert.assertTrue(diagnostics.any { it.contains("Daily 04:00 reset applied") })
        assertEquals(0L, postReset.budgetSnapshots.single().limitMinutes)
        assertEquals(0L, postReset.budgetSnapshots.single().usedMs)
        // Pre-reset charged time was 1000ms. Only pre-reset 1000ms is recorded in report
        assertEquals(1_000L, postReset.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs)
        assertNotNull(postReset.activeUnlock)
        assertEquals(com.michalkrsik.internetrestrictor.domain.GateMode.OUT_OF_TIME, postReset.canonicalMode)

        // Next tick: gate is OUT_OF_TIME, forcing summary flush of pre-reset usage
        timeSource.advance(1_000L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // Pre-reset charged time was 1000ms. The diagnostic summary must record charged=1000ms, not 0ms!
        val summary = diagnostics.filter { it.contains("Usage committed:") }.last()
        org.junit.Assert.assertTrue(summary.contains("recorded=1000ms, charged=1000ms"))
        assertEquals(1_000L, repository.snapshot.value.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs)
        assertEquals(0L, repository.snapshot.value.budgetSnapshots.single().usedMs)
    }

    @Test
    fun subsecondUsageSurvivesDailyReset() = kotlinx.coroutines.runBlocking {
        val zone = java.time.ZoneId.of("UTC")
        val resetWallMs = java.time.ZonedDateTime.of(2026, 7, 11, 4, 0, 0, 0, zone).toInstant().toEpochMilli()
        val startWallMs = resetWallMs - 500L
        val timeSource = com.michalkrsik.internetrestrictor.domain.FakeTimeSource(wallMs = startWallMs, elapsedMs = 1_000_000L, zone = zone)
        val testScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()

        val initial = PersistedState(
            dailyCounter = com.michalkrsik.internetrestrictor.domain.DailyCounter(
                localDayKey = "2026-07-10",
                earnedBudgetRounds = 5,
                spendableBudgetRounds = 5,
            ),
            dailyUsage = com.michalkrsik.internetrestrictor.domain.UsagePeriodState(
                period = com.michalkrsik.internetrestrictor.domain.BudgetPeriod.DAILY,
                key = "2026-07-10",
                usedMs = 60_000L,
            ),
            activeUnlock = com.michalkrsik.internetrestrictor.domain.UnlockSession(
                id = "normal-unlock",
                source = com.michalkrsik.internetrestrictor.domain.UnlockSource.NORMAL,
                grantedAtWallMs = startWallMs,
                expiresAtWallMs = startWallMs + 60_000L,
            ),
        )

        val repository = RestrictorRepository(
            timeSource = timeSource,
            applicationScope = testScope,
            diagnosticSink = { diagnostics += it },
            dataStoreOverride = InMemoryDataStore(initial),
        )
        repository.awaitLoaded()
        repository.updateDeviceAccess(
            accessibilityEnabled = true,
            usageAccessGranted = true,
            supportedBrowserInstalled = true,
            advancedProtectionEnabled = true,
        )
        repository.updateServiceRuntime(
            serviceConnected = true,
            foregroundClass = ForegroundClass.BROWSER,
            activeTarget = com.michalkrsik.internetrestrictor.domain.ProtectedTarget.BROWSER_POOL,
            screenInteractive = true,
            keyguardUnlocked = true,
        )

        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // Two ticks shorter than the usage heartbeat must flush at the reset.
        timeSource.advance(250L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
        timeSource.advance(250L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        val postReset = repository.snapshot.value
        // Daily reset was applied: spendable rounds reset to 0, usedMs reset to 0
        org.junit.Assert.assertTrue(diagnostics.any { it.contains("Daily 04:00 reset applied") })
        assertEquals(0L, postReset.budgetSnapshots.single().limitMinutes)
        assertEquals(0L, postReset.budgetSnapshots.single().usedMs)
        // Pre-reset charged time was 500ms. Only pre-reset 500ms is recorded in report
        assertEquals(500L, postReset.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs)
        assertNotNull(postReset.activeUnlock)
        assertEquals(com.michalkrsik.internetrestrictor.domain.GateMode.OUT_OF_TIME, postReset.canonicalMode)

        // Next tick: gate is OUT_OF_TIME, forcing summary flush of pre-reset usage
        timeSource.advance(1_000L)
        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())

        // Pre-reset charged time was 500ms. The diagnostic summary must record charged=500ms, not 0ms!
        val summary = diagnostics.filter { it.contains("Usage committed:") }.last()
        org.junit.Assert.assertTrue(summary.contains("recorded=500ms, charged=500ms"))
        assertEquals(500L, repository.snapshot.value.usageReports.single { it.localDayKey == "2026-07-10" }.protectedUsageMs)
        assertEquals(0L, repository.snapshot.value.budgetSnapshots.single().usedMs)
    }
}

private class InMemoryDataStore(initial: PersistedState) : androidx.datastore.core.DataStore<PersistedState> {
    private val flow = kotlinx.coroutines.flow.MutableStateFlow(initial)
    override val data: kotlinx.coroutines.flow.Flow<PersistedState> = flow
    override suspend fun updateData(transform: suspend (t: PersistedState) -> PersistedState): PersistedState {
        val next = transform(flow.value)
        flow.value = next
        return next
    }
}

private class FailingRewardStore(initial: PersistedState) : androidx.datastore.core.DataStore<PersistedState> {
    private val state = kotlinx.coroutines.flow.MutableStateFlow(initial)
    var fail = false
    override val data: kotlinx.coroutines.flow.Flow<PersistedState> = state
    override suspend fun updateData(transform: suspend (PersistedState) -> PersistedState): PersistedState {
        if (fail) throw java.io.IOException("Disk full")
        return transform(state.value).also { state.value = it }
    }
}
