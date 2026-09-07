package com.michalkrsik.internetrestrictor.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import com.michalkrsik.internetrestrictor.domain.CommandResult
import com.michalkrsik.internetrestrictor.domain.ExerciseId
import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.GateMode
import com.michalkrsik.internetrestrictor.domain.IdGenerator
import com.michalkrsik.internetrestrictor.domain.PersistedState
import com.michalkrsik.internetrestrictor.domain.ProtectedTarget
import com.michalkrsik.internetrestrictor.domain.RandomIdGenerator
import com.michalkrsik.internetrestrictor.domain.RestrictionEngine
import com.michalkrsik.internetrestrictor.domain.RestrictorSettings
import com.michalkrsik.internetrestrictor.domain.RuntimeSignals
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.domain.TimeSource
import com.michalkrsik.internetrestrictor.domain.UnlockSource
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RestrictorRepository(
    context: Context? = null,
    private val timeSource: TimeSource,
    private val applicationScope: CoroutineScope,
    private val engine: RestrictionEngine = RestrictionEngine(),
    private val idGenerator: IdGenerator = RandomIdGenerator,
    private val diagnosticSink: (String) -> Unit = {},
    dataStoreOverride: DataStore<PersistedState>? = null,
) {
    private val dataStore = dataStoreOverride ?: DataStoreFactory.create(
        serializer = AppStateSerializer,
        scope = applicationScope,
        produceFile = {
            val ctx = requireNotNull(context) { "Context required when dataStoreOverride is null" }
            File(ctx.filesDir, "datastore/restrictor_state.bin")
        },
    )
    private val mutationMutex = Mutex()
    private val loaded = CompletableDeferred<Unit>()
    private var persistedState = PersistedState()
    private var runtime = RuntimeSignals()
    private var isLoaded = false
    private var lastError: String? = null
    private var lastTickElapsedMs: Long? = null
    private var lastTickWallMs: Long? = null
    private var bufferedUsageStartWallMs: Long? = null
    private var bufferedUsageEndWallMs: Long? = null
    private var accumulatedRecordedMs: Long = 0L
    private var accumulatedChargedMs: Long = 0L

    private val _snapshot = MutableStateFlow(
        engine.snapshot(
            persistedState,
            runtime,
            timeSource.wallTimeMs(),
            zoneId = timeSource.zoneId(),
            isLoaded = false,
        ),
    )
    val snapshot: StateFlow<StateSnapshot> = _snapshot.asStateFlow()

    init {
        applicationScope.launch {
            try {
                val stored = dataStore.data.first()
                mutationMutex.withLock {
                    val normalized = engine.normalize(stored, timeSource.wallTimeMs(), timeSource.zoneId())
                    if (normalized != stored) dataStore.updateData { normalized }
                    persistedState = normalized
                    isLoaded = true
                    runtime = runtime.copy(storageReady = true, storageError = false)
                    manageGateTokensLocked()
                    publishLocked()
                    diagnosticSink("State store loaded")
                }
            } catch (error: Exception) {
                mutationMutex.withLock {
                    isLoaded = true
                    lastError = "Stored state could not be trusted. Open app info to clear data, or reinstall."
                    runtime = runtime.copy(storageReady = false, storageError = true)
                    publishLocked()
                    diagnosticSink("State store read failed")
                }
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    suspend fun updateDeviceAccess(
        accessibilityEnabled: Boolean,
        usageAccessGranted: Boolean,
        supportedBrowserInstalled: Boolean,
        advancedProtectionEnabled: Boolean,
    ) {
        awaitLoaded()
        mutationMutex.withLock {
            advanceLocked(
                timeSource.elapsedRealtimeMs(),
                timeSource.wallTimeMs(),
                forceUsageFlush = true,
            )
            val old = runtime
            runtime = runtime.copy(
                accessibilityEnabled = accessibilityEnabled,
                usageAccessGranted = usageAccessGranted,
                supportedBrowserInstalled = supportedBrowserInstalled,
                advancedProtectionEnabled = advancedProtectionEnabled,
            )
            manageGateTokensLocked()
            if (old.healthFingerprint() != runtime.healthFingerprint()) bumpRevisionLocked()
            publishLocked()
        }
    }

    suspend fun updateServiceRuntime(
        foregroundClass: ForegroundClass = runtime.foregroundClass,
        activeTarget: ProtectedTarget? = runtime.activeTarget,
        gateFocused: Boolean = runtime.gateFocused,
        screenInteractive: Boolean = runtime.screenInteractive,
        keyguardUnlocked: Boolean = runtime.keyguardUnlocked,
        serviceConnected: Boolean = runtime.serviceConnected,
    ) {
        awaitLoaded()
        mutationMutex.withLock {
            advanceLocked(
                timeSource.elapsedRealtimeMs(),
                timeSource.wallTimeMs(),
                forceUsageFlush = true,
            )
            val old = runtime
            val waitWasPending = persistedState.pendingWait != null
            val updatedState = abortPendingWaitAfterServiceUpdate(
                state = persistedState,
                browserActive = activeTarget == ProtectedTarget.BROWSER_POOL,
            )
            val waitAborted = waitWasPending && updatedState.pendingWait == null
            if (waitAborted) {
                persistedState = updatedState
            }
            runtime = runtime.copy(
                foregroundClass = foregroundClass,
                activeTarget = activeTarget,
                gateFocused = gateFocused,
                screenInteractive = screenInteractive,
                keyguardUnlocked = keyguardUnlocked,
                serviceConnected = serviceConnected,
            )
            if (serviceConnected && !old.serviceConnected) {
                persistedState = persistedState.copy(
                    runtimeMetadata = persistedState.runtimeMetadata.copy(
                        serviceLastConnectedWallMs = timeSource.wallTimeMs(),
                    ),
                )
            }
            persistedState = persistedState.copy(
                runtimeMetadata = persistedState.runtimeMetadata.copy(
                    lastKnownForegroundClass = persistedForegroundAfterServiceUpdate(
                        previous = persistedState.runtimeMetadata.lastKnownForegroundClass,
                        observed = foregroundClass,
                        serviceConnected = serviceConnected,
                    ),
                ),
            )
            manageGateTokensLocked()
            if (old.policyFingerprint() != runtime.policyFingerprint() || waitAborted) {
                bumpRevisionLocked()
            }
            if (waitAborted) diagnosticSink("Focused wait aborted after leaving the browser")
            if (old.foregroundClass != runtime.foregroundClass || old.activeTarget != runtime.activeTarget) {
                diagnosticSink(
                    "Foreground transition: ${runtime.activeTarget?.name ?: runtime.foregroundClass.name}",
                )
            }
            publishLocked()
        }
    }

    suspend fun tick(elapsedRealtimeMs: Long, wallTimeMs: Long) {
        awaitLoaded()
        mutationMutex.withLock {
            advanceLocked(elapsedRealtimeMs, wallTimeMs, forceUsageFlush = false)
            publishLocked(wallTimeMs)
        }
    }

    suspend fun flushAccounting() {
        awaitLoaded()
        mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            publishLocked()
        }
    }

    suspend fun startWait(expectedRevision: Long, requestedMinutes: Long): Boolean = command {
        engine.startWait(
            input = persistedState,
            runtime = runtime,
            expectedRevision = expectedRevision,
            requestedMinutes = requestedMinutes,
            nowWallMs = timeSource.wallTimeMs(),
            zoneId = timeSource.zoneId(),
        )
    }

    suspend fun emergencySkip(expectedRevision: Long, expectedPendingWaitId: String?): Boolean = command(
        clearOutOfTimeLatch = true,
    ) {
        engine.emergencySkip(
            input = persistedState,
            runtime = runtime,
            expectedRevision = expectedRevision,
            expectedPendingWaitId = expectedPendingWaitId,
            nowWallMs = timeSource.wallTimeMs(),
            zoneId = timeSource.zoneId(),
        )
    }

    suspend fun recordPushupRepetitions(repetitions: Int): Boolean =
        recordVerifiedExercise(
            verificationId = idGenerator.nextId(),
            exerciseId = ExerciseId.PUSH_UP,
            value = repetitions,
        )

    suspend fun recordVerifiedExercise(
        verificationId: String,
        exerciseId: ExerciseId,
        value: Int,
    ): Boolean = command(clearOutOfTimeLatch = true) {
        engine.recordVerifiedExercise(
            input = persistedState,
            verificationId = verificationId,
            exerciseId = exerciseId,
            value = value.toLong(),
            nowWallMs = timeSource.wallTimeMs(),
            zoneId = timeSource.zoneId(),
        )
    }

    suspend fun tryAgain(
        expectedRevision: Long,
        expectedGateSessionId: String?,
        expectedLatchToken: String?,
    ): Boolean {
        awaitLoaded()
        return mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            val current = engine.snapshot(
                persistedState,
                runtime,
                timeSource.wallTimeMs(),
                zoneId = timeSource.zoneId(),
                isLoaded = isLoaded,
            )
            val valid = current.stateRevision == expectedRevision &&
                current.canonicalMode in setOf(GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED) &&
                runtime.gateSessionId != null && runtime.gateSessionId == expectedGateSessionId &&
                runtime.outOfTimeLatchToken != null &&
                runtime.outOfTimeLatchToken == expectedLatchToken
            if (!valid) {
                lastError = "Browser state changed. The latest gate is still active."
                publishLocked()
                return@withLock false
            }
            val normalized = engine.normalize(persistedState, timeSource.wallTimeMs(), timeSource.zoneId())
            val next = normalized.copy(stateRevision = Math.addExact(normalized.stateRevision, 1))
            if (!persistLocked(next)) return@withLock false
            persistedState = next
            runtime = runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
            diagnosticSink("Out-of-time retry evaluated")
            manageGateTokensLocked()
            lastError = null
            publishLocked()
            true
        }
    }

    suspend fun saveSettings(expectedRevision: Long, settings: RestrictorSettings): Boolean = command(
        clearOutOfTimeLatch = true,
    ) {
        engine.saveSettings(
            input = persistedState,
            newSettings = settings,
            expectedRevision = expectedRevision,
            nowWallMs = timeSource.wallTimeMs(),
            zoneId = timeSource.zoneId(),
        )
    }

    suspend fun setTheme(theme: ThemePreference): Boolean = immediateMutation {
        engine.updateImmediateSettings(it, { settings -> settings.copy(theme = theme) }, timeSource.wallTimeMs(), timeSource.zoneId())
    }

    suspend fun completeOnboarding(): Boolean = immediateMutation {
        engine.updateImmediateSettings(it, { settings -> settings.copy(onboardingCompleted = true) }, timeSource.wallTimeMs(), timeSource.zoneId())
    }

    suspend fun clearReportHistory(): Boolean = immediateMutation {
        engine.clearReportHistory(it)
    }

    suspend fun cancelActiveUnlock(): Boolean {
        awaitLoaded()
        return mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            val hadActiveUnlock = persistedState.activeUnlock != null
            val next = engine.cancelActiveUnlock(
                persistedState,
                timeSource.wallTimeMs(),
                timeSource.zoneId(),
            )
            if (!persistLocked(next)) return@withLock false
            persistedState = next
            runtime = runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
            if (hadActiveUnlock && next.activeUnlock == null) {
                diagnosticSink("Active unlock cancelled from developer controls")
            }
            manageGateTokensLocked()
            lastError = null
            publishLocked()
            true
        }
    }

    suspend fun handleClockOrTimezoneChanged() {
        awaitLoaded()
        mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            val nowWallMs = timeSource.wallTimeMs()
            val normalized = engine.normalize(persistedState, nowWallMs, timeSource.zoneId())
            val next = normalized.copy(
                runtimeMetadata = normalized.runtimeMetadata.copy(
                    recoveryEpochId = idGenerator.nextId(),
                    lastCommittedWallMs = nowWallMs,
                ),
            )
            if (persistLocked(next)) persistedState = next
            diagnosticSink("Clock or timezone boundary normalized")
            resetTickAnchorsLocked()
            manageGateTokensLocked()
            publishLocked()
        }
    }

    private suspend fun command(
        clearOutOfTimeLatch: Boolean = false,
        transition: () -> CommandResult,
    ): Boolean {
        awaitLoaded()
        return mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            when (val result = transition()) {
                is CommandResult.Applied -> {
                    val before = persistedState
                    if (!persistLocked(result.state)) return@withLock false
                    persistedState = result.state
                    if (clearOutOfTimeLatch) {
                        runtime = runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
                    }
                    manageGateTokensLocked()
                    recordTransition(before, result.state)
                    lastError = null
                    publishLocked()
                    true
                }
                is CommandResult.NormalCompletionWon -> {
                    val before = persistedState
                    if (!persistLocked(result.state)) return@withLock false
                    persistedState = result.state
                    manageGateTokensLocked()
                    recordTransition(before, result.state)
                    lastError = null
                    publishLocked()
                    true
                }
                is CommandResult.Rejected -> {
                    if (result.state != persistedState) {
                        if (!persistLocked(result.state)) return@withLock false
                        persistedState = result.state
                    }
                    lastError = result.reason
                    diagnosticSink("Action rejected: ${result.reason}")
                    manageGateTokensLocked()
                    publishLocked()
                    false
                }
            }
        }
    }

    private suspend fun immediateMutation(
        clearOutOfTimeLatch: Boolean = false,
        transform: (PersistedState) -> PersistedState,
    ): Boolean {
        awaitLoaded()
        return mutationMutex.withLock {
            advanceLocked(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs(), forceUsageFlush = true)
            val next = transform(persistedState)
            if (!persistLocked(next)) return@withLock false
            persistedState = next
            if (clearOutOfTimeLatch) {
                runtime = runtime.copy(outOfTimeLatchToken = null, latchedBlockers = emptySet())
            }
            manageGateTokensLocked()
            lastError = null
            publishLocked()
            true
        }
    }

    private suspend fun advanceLocked(
        elapsedRealtimeMs: Long,
        wallTimeMs: Long,
        forceUsageFlush: Boolean,
    ) {
        val previousElapsed = lastTickElapsedMs
        val previousWall = lastTickWallMs
        lastTickElapsedMs = elapsedRealtimeMs
        lastTickWallMs = wallTimeMs
        if (previousElapsed == null || previousWall == null) {
            val normalized = engine.normalize(persistedState, wallTimeMs, timeSource.zoneId())
            if (normalized != persistedState) {
                val before = persistedState
                if (persistLocked(normalized)) {
                    persistedState = normalized
                    recordTransition(before, normalized)
                }
            }
            manageGateTokensLocked()
            return
        }

        val rawDelta = elapsedRealtimeMs - previousElapsed
        if (rawDelta <= 0) {
            if (forceUsageFlush) flushUsageBufferLocked(forceSummaryFlush = true)
            val normalized = engine.normalize(persistedState, wallTimeMs, timeSource.zoneId())
            if (normalized != persistedState) {
                val before = persistedState
                if (persistLocked(normalized)) {
                    persistedState = normalized
                    recordTransition(before, normalized)
                }
            }
            manageGateTokensLocked()
            return
        }
        val elapsedDelta = rawDelta.coerceAtMost(MAX_UNRECONCILED_INTERVAL_MS)
        if (rawDelta > MAX_UNRECONCILED_INTERVAL_MS) {
            diagnosticSink("Long accounting gap left uncounted: ${rawDelta}ms")
        }
        val wallDelta = wallTimeMs - previousWall
        val intervalStart = if (abs(wallDelta - rawDelta) <= CLOCK_DRIFT_TOLERANCE_MS) {
            previousWall
        } else {
            previousWall
        }
        val intervalEnd = Math.addExact(intervalStart, elapsedDelta)
        val mode = engine.underlyingMode(persistedState, runtime, intervalStart)

        when (mode) {
            GateMode.WAITING -> {
                flushUsageBufferLocked(forceSummaryFlush = true)
                val result = engine.advanceWait(
                    input = persistedState,
                    runtime = runtime,
                    elapsedMs = elapsedDelta,
                    nowWallMs = intervalEnd,
                    zoneId = timeSource.zoneId(),
                    expectedGateSessionId = runtime.gateSessionId,
                )
                val next = when (result) {
                    is CommandResult.Applied -> result.state
                    is CommandResult.NormalCompletionWon -> result.state
                    is CommandResult.Rejected -> result.state
                }
                if (next != persistedState) {
                    val before = persistedState
                    if (persistLocked(next)) {
                        persistedState = next
                        recordTransition(before, next)
                    }
                }
            }
            GateMode.ACCESS_GRANTED_NORMAL,
            GateMode.ACCESS_GRANTED_EMERGENCY,
            GateMode.ACCESS_GRANTED_NO_DELAY,
            -> {
                val cappedEnd = persistedState.activeUnlock?.expiresAtWallMs
                    ?.coerceAtMost(intervalEnd)
                    ?.coerceAtLeast(intervalStart)
                    ?: intervalEnd
                if (cappedEnd > intervalStart) {
                    if (bufferedUsageStartWallMs == null) bufferedUsageStartWallMs = intervalStart
                    bufferedUsageEndWallMs = cappedEnd
                }
                val bufferedDuration = (bufferedUsageEndWallMs ?: 0) - (bufferedUsageStartWallMs ?: 0)
                val tightRemaining = engine.budgetSnapshots(persistedState)
                    .filter { it.enabled }
                    .minOfOrNull { it.remainingMs ?: Long.MAX_VALUE }
                val isEmergency = persistedState.activeUnlock?.source == UnlockSource.EMERGENCY
                val boundaryReached = !isEmergency && tightRemaining != null && bufferedDuration >= tightRemaining
                val unlockReached = persistedState.activeUnlock?.expiresAtWallMs?.let { it <= wallTimeMs } == true
                if (forceUsageFlush || bufferedDuration >= USAGE_HEARTBEAT_MS || boundaryReached || unlockReached) {
                    flushUsageBufferLocked(forceSummaryFlush = forceUsageFlush || boundaryReached || unlockReached)
                }
            }
            else -> flushUsageBufferLocked(forceSummaryFlush = true)
        }

        val normalized = engine.normalize(persistedState, wallTimeMs, timeSource.zoneId())
        if (normalized != persistedState) {
            val before = persistedState
            if (persistLocked(normalized)) {
                persistedState = normalized
                recordTransition(before, normalized)
            }
        }

        manageGateTokensLocked()
    }

    private suspend fun flushUsageBufferLocked(forceSummaryFlush: Boolean = false) {
        val start = bufferedUsageStartWallMs
        val end = bufferedUsageEndWallMs
        bufferedUsageStartWallMs = null
        bufferedUsageEndWallMs = null
        var previousWasEmergency = false
        if (start != null && end != null && end > start) {
            val before = persistedState
            val wasEmergency = before.activeUnlock?.source == UnlockSource.EMERGENCY
            previousWasEmergency = wasEmergency
            val consumption = engine.consumeUsageDetailed(before, runtime, start, end, timeSource.zoneId())
            val next = consumption.state
            if (next != before && persistLocked(next)) {
                val becameExhausted = !engine.budgetSnapshots(before).any { it.exhausted } &&
                    engine.budgetSnapshots(next).any { it.exhausted }
                accumulatedRecordedMs = Math.addExact(accumulatedRecordedMs, consumption.recordedMs)
                accumulatedChargedMs = Math.addExact(accumulatedChargedMs, consumption.chargedMs)
                recordTransition(before, next)
                if (becameExhausted) {
                    flushUsageSummaryLocked(wasEmergency)
                    diagnosticSink("Browser budget exhausted")
                }
            }
        }
        if (forceSummaryFlush || accumulatedRecordedMs >= USAGE_SUMMARY_INTERVAL_MS) {
            flushUsageSummaryLocked(previousWasEmergency || persistedState.activeUnlock?.source == UnlockSource.EMERGENCY)
        }
    }

    private fun flushUsageSummaryLocked(emergencyActive: Boolean) {
        if (accumulatedRecordedMs <= 0) return
        val emergencyContext = if (emergencyActive) " (emergency active)" else ""
        diagnosticSink("Usage committed: recorded=${accumulatedRecordedMs}ms, charged=${accumulatedChargedMs}ms$emergencyContext (spendable=${persistedState.dailyCounter.spendableBudgetRounds} sets, used=${persistedState.dailyUsage.usedMs}ms)")
        accumulatedRecordedMs = 0L
        accumulatedChargedMs = 0L
    }

    private suspend fun bumpRevisionLocked() {
        if (!runtime.storageReady || runtime.storageError) return
        val next = persistedState.copy(stateRevision = Math.addExact(persistedState.stateRevision, 1))
        if (persistLocked(next)) persistedState = next
    }

    private suspend fun persistLocked(next: PersistedState): Boolean = try {
        val written = dataStore.updateData { next }
        persistedState = written
        runtime = runtime.copy(storageReady = true, storageError = false, storageWriteError = false)
        lastError = null
        true
    } catch (error: Exception) {
        runtime = runtime.copy(storageWriteError = true)
        lastError = "A state change could not be saved. The requested change was not applied."
        diagnosticSink("State store write failed")
        publishLocked()
        false
    }

    private fun manageGateTokensLocked() {
        val underlying = engine.underlyingMode(persistedState, runtime, timeSource.wallTimeMs())
        if (runtime.activeTarget == null) {
            runtime = runtime.copy(
                gateSessionId = null,
                outOfTimeLatchToken = null,
                latchedBlockers = emptySet(),
                gateFocused = false,
            )
            return
        }
        if (runtime.activeTarget != ProtectedTarget.BROWSER_POOL) {
            runtime = runtime.copy(
                outOfTimeLatchToken = null,
                latchedBlockers = emptySet(),
            )
            if (underlying in nonGateModes) {
                runtime = runtime.copy(gateSessionId = null, gateFocused = false)
            } else if (runtime.gateSessionId == null) {
                runtime = runtime.copy(gateSessionId = idGenerator.nextId())
            }
            return
        }
        if (runtime.outOfTimeLatchToken != null) {
            if (runtime.gateSessionId == null) runtime = runtime.copy(gateSessionId = idGenerator.nextId())
            return
        }
        if (underlying in nonGateModes) {
            runtime = runtime.copy(gateSessionId = null, gateFocused = false)
            return
        }
        if (runtime.gateSessionId == null) runtime = runtime.copy(gateSessionId = idGenerator.nextId())
        if (underlying == GateMode.OUT_OF_TIME && runtime.outOfTimeLatchToken == null) {
            runtime = runtime.copy(
                outOfTimeLatchToken = idGenerator.nextId(),
                latchedBlockers = engine.budgetSnapshots(persistedState)
                    .filter { it.exhausted }
                    .mapTo(mutableSetOf()) { it.period },
            )
        }
    }

    private fun publishLocked(wallTimeMs: Long = timeSource.wallTimeMs()) {
        _snapshot.value = engine.snapshot(
            state = persistedState,
            runtime = runtime,
            nowWallMs = wallTimeMs,
            zoneId = timeSource.zoneId(),
            isLoaded = isLoaded,
            lastErrorMessage = lastError,
        )
    }

    private fun resetTickAnchorsLocked() {
        lastTickElapsedMs = null
        lastTickWallMs = null
        bufferedUsageStartWallMs = null
        bufferedUsageEndWallMs = null
        accumulatedRecordedMs = 0L
        accumulatedChargedMs = 0L
    }

    private fun recordTransition(before: PersistedState, after: PersistedState) {
        if (before.pendingWait == null && after.pendingWait != null) {
            diagnosticSink("Focused wait started")
        }
        if (before.activeUnlock == null && after.activeUnlock?.source == UnlockSource.NORMAL) {
            diagnosticSink("Normal unlock granted")
        }
        if (before.activeUnlock?.source != UnlockSource.EMERGENCY && after.activeUnlock?.source == UnlockSource.EMERGENCY) {
            diagnosticSink("Emergency access granted (15m free window, spendable=${after.dailyCounter.spendableBudgetRounds} sets, used=${after.dailyUsage.usedMs}ms)")
        }
        if (before.activeUnlock?.source == UnlockSource.EMERGENCY && after.activeUnlock == null) {
            diagnosticSink("Emergency access expired (spendable=${after.dailyCounter.spendableBudgetRounds} sets, used=${after.dailyUsage.usedMs}ms)")
        }
        if (before.activeUnlock?.source == UnlockSource.NORMAL && after.activeUnlock == null) {
            diagnosticSink("Normal unlock expired (spendable=${after.dailyCounter.spendableBudgetRounds} sets, used=${after.dailyUsage.usedMs}ms)")
        }
        if (!before.dailyCounter.eveningResetApplied && after.dailyCounter.eveningResetApplied) {
            diagnosticSink("Evening 20:00 reset applied (spendable reset from ${before.dailyCounter.spendableBudgetRounds} to 0, usedMs reset from ${before.dailyUsage.usedMs} to 0)")
        }
        if (before.dailyCounter.localDayKey.isNotBlank() && before.dailyCounter.localDayKey != after.dailyCounter.localDayKey) {
            diagnosticSink("Daily 04:00 reset applied for day ${after.dailyCounter.localDayKey} (previous earned=${before.dailyCounter.earnedBudgetRounds}, usedMs=${before.dailyUsage.usedMs})")
        }
        if (before.dailyCounter.earnedBudgetRounds < after.dailyCounter.earnedBudgetRounds) {
            diagnosticSink("Exercise sets added budget for today")
        }
        if (before.dailyCounter.exercisePointSixths != after.dailyCounter.exercisePointSixths ||
            before.dailyCounter.pushupRepetitionRemainder != after.dailyCounter.pushupRepetitionRemainder ||
            before.dailyCounter.plankSecondRemainder != after.dailyCounter.plankSecondRemainder ||
            before.dailyCounter.machineRepetitionRemainder != after.dailyCounter.machineRepetitionRemainder
        ) {
            diagnosticSink("Partial exercise progress saved for today")
        }
        if (before.settings != after.settings) {
            diagnosticSink("Settings changed")
        }
    }

    private fun RuntimeSignals.policyFingerprint() = listOf(
        foregroundClass,
        activeTarget,
        gateFocused,
        screenInteractive,
        keyguardUnlocked,
        serviceConnected,
        accessibilityEnabled,
        storageReady,
        storageError,
        storageWriteError,
        gateSessionId,
        outOfTimeLatchToken,
    )

    private fun RuntimeSignals.healthFingerprint() = listOf(
        accessibilityEnabled,
        usageAccessGranted,
        supportedBrowserInstalled,
        advancedProtectionEnabled,
        serviceConnected,
        storageReady,
        storageError,
        storageWriteError,
    )

    private companion object {
        const val USAGE_HEARTBEAT_MS = 1_000L
        const val USAGE_SUMMARY_INTERVAL_MS = 60_000L
        const val MAX_UNRECONCILED_INTERVAL_MS = 5_000L
        const val CLOCK_DRIFT_TOLERANCE_MS = 2_000L
        val nonGateModes = setOf(
            GateMode.ACCESS_GRANTED_NORMAL,
            GateMode.ACCESS_GRANTED_EMERGENCY,
            GateMode.ACCESS_GRANTED_NO_DELAY,
            GateMode.YOUTUBE_ACCESS_GRANTED,
            GateMode.BROWSER_BACKGROUND,
            GateMode.SERVICE_UNAVAILABLE,
            GateMode.STORAGE_ERROR,
        )
    }
}

internal fun persistedForegroundAfterServiceUpdate(
    previous: ForegroundClass,
    observed: ForegroundClass,
    serviceConnected: Boolean,
): ForegroundClass = if (serviceConnected) observed else previous

internal fun abortPendingWaitAfterServiceUpdate(
    state: PersistedState,
    browserActive: Boolean,
): PersistedState = if (!browserActive && state.pendingWait != null) {
    state.copy(pendingWait = null)
} else {
    state
}
