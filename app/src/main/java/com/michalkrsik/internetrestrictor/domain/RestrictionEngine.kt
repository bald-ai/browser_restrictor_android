package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId
import kotlin.math.roundToInt

class RestrictionEngine(
    private val idGenerator: IdGenerator = RandomIdGenerator,
) {
    fun validateSettings(settings: RestrictorSettings): Boolean = listOf(
        settings.dailyBudgetMinutes,
        settings.weeklyBudgetMinutes,
        settings.monthlyBudgetMinutes,
        settings.emergencySkipDailyLimit,
    ).all { it in 0..MAX_SAFE_MINUTES }

    fun normalize(input: PersistedState, nowWallMs: Long, zoneId: ZoneId): PersistedState {
        val keys = PeriodCalculator.keysAt(nowWallMs, zoneId)
        var state = input
        var revisionRelevant = false

        fun normalizedUsage(usage: UsagePeriodState, expectedKey: String): UsagePeriodState {
            if (usage.key == expectedKey) return usage
            revisionRelevant = true
            return usage.copy(key = expectedKey, usedMs = 0)
        }

        state = state.copy(
            dailyUsage = normalizedUsage(state.dailyUsage, keys.daily),
            weeklyUsage = normalizedUsage(state.weeklyUsage, keys.weekly),
            monthlyUsage = normalizedUsage(state.monthlyUsage, keys.monthly),
        )

        if (state.dailyCounter.localDayKey != keys.daily) {
            state = state.copy(dailyCounter = DailyCounter(localDayKey = keys.daily))
            revisionRelevant = true
        }

        state.activeUnlock?.let { unlock ->
            if (unlock.expiresAtWallMs <= nowWallMs) {
                state = state.copy(activeUnlock = null)
                revisionRelevant = true
            }
        }

        state.pendingWait?.let { pending ->
            val expired = nowWallMs < pending.createdAtWallMs ||
                nowWallMs - pending.createdAtWallMs >= PENDING_WAIT_EXPIRY_MS
            if (expired) {
                state = state.copy(pendingWait = null)
                revisionRelevant = true
            }
        }

        return if (revisionRelevant) state.copy(stateRevision = nextRevision(state.stateRevision)) else state
    }

    fun budgetSnapshots(state: PersistedState): List<BudgetSnapshot> = BudgetPeriod.entries.map { period ->
        val usage = state.usage(period)
        val (enabled, minutes) = when (period) {
            BudgetPeriod.DAILY -> state.settings.dailyBudgetEnabled to state.settings.dailyBudgetMinutes
            BudgetPeriod.WEEKLY -> state.settings.weeklyBudgetEnabled to state.settings.weeklyBudgetMinutes
            BudgetPeriod.MONTHLY -> state.settings.monthlyBudgetEnabled to state.settings.monthlyBudgetMinutes
        }
        val limitMs = safeMinutesToMs(minutes)
        val remaining = if (enabled) (limitMs - usage.usedMs).coerceAtLeast(0) else null
        val exhausted = enabled && remaining == 0L
        val percentage = when {
            !enabled -> 0
            limitMs == 0L -> 100
            else -> ((usage.usedMs.coerceAtMost(limitMs).toDouble() / limitMs) * 100).roundToInt()
        }
        BudgetSnapshot(
            period = period,
            enabled = enabled,
            limitMinutes = minutes,
            usedMs = usage.usedMs,
            remainingMs = remaining,
            percentage = percentage,
            exhausted = exhausted,
        )
    }

    fun underlyingMode(state: PersistedState, runtime: RuntimeSignals, nowWallMs: Long): GateMode {
        if (runtime.storageError) return GateMode.STORAGE_ERROR
        if (!runtime.chromeActive || !runtime.screenInteractive || !runtime.keyguardUnlocked) {
            return GateMode.CHROME_BACKGROUND
        }
        if (!state.settings.enforcementEnabled) return GateMode.ENFORCEMENT_DISABLED
        if (!runtime.storageReady || !runtime.accessibilityEnabled || !runtime.serviceConnected) {
            return GateMode.SERVICE_UNAVAILABLE
        }
        if (budgetSnapshots(state).any { it.exhausted }) return GateMode.OUT_OF_TIME
        state.activeUnlock?.takeIf { it.expiresAtWallMs > nowWallMs }?.let {
            return if (it.source == UnlockSource.NORMAL) {
                GateMode.ACCESS_GRANTED_NORMAL
            } else {
                GateMode.ACCESS_GRANTED_EMERGENCY
            }
        }
        if (!state.settings.delayEnabled) return GateMode.ACCESS_GRANTED_NO_DELAY
        if (state.pendingWait != null) return GateMode.WAITING
        return GateMode.PAUSED
    }

    fun snapshot(
        state: PersistedState,
        runtime: RuntimeSignals,
        nowWallMs: Long,
        isLoaded: Boolean = true,
        lastErrorMessage: String? = null,
    ): StateSnapshot {
        val budgets = budgetSnapshots(state)
        val underlying = underlyingMode(state, runtime, nowWallMs)
        val mode = if (
            underlying != GateMode.OUT_OF_TIME &&
            runtime.outOfTimeLatchToken != null &&
            runtime.chromeActive &&
            state.settings.enforcementEnabled &&
            runtime.serviceConnected
        ) {
            GateMode.OUT_OF_TIME_LATCHED
        } else {
            underlying
        }
        val pending = state.pendingWait
        val waitSnapshot = pending?.let {
            val remaining = (it.requiredFocusMs - it.accumulatedFocusMs).coerceAtLeast(0)
            WaitSnapshot(
                pendingWaitId = it.id,
                selectedDurationMs = it.selectedDurationMs,
                requiredFocusMs = it.requiredFocusMs,
                accumulatedFocusMs = it.accumulatedFocusMs,
                remainingFocusMs = remaining,
                progress = if (it.requiredFocusMs == 0L) 1f else
                    (it.accumulatedFocusMs.toDouble() / it.requiredFocusMs).toFloat().coerceIn(0f, 1f),
            )
        }
        val remainingSkips = (
            state.settings.emergencySkipDailyLimit - state.dailyCounter.emergencySkipsUsed
        ).coerceAtLeast(0)
        val enabledBudgets = budgets.filter { it.enabled }
        val grantDuration = if (enabledBudgets.isEmpty()) {
            EMERGENCY_UNLOCK_MS
        } else {
            minOf(EMERGENCY_UNLOCK_MS, enabledBudgets.minOf { it.remainingMs ?: 0 })
        }
        val skipEligible = state.settings.emergencySkipsEnabled &&
            remainingSkips > 0 &&
            grantDuration > 0 &&
            !budgets.any { it.exhausted } &&
            mode in setOf(GateMode.PAUSED, GateMode.WAITING) &&
            state.activeUnlock == null &&
            state.settings.delayEnabled

        return StateSnapshot(
            stateRevision = state.stateRevision,
            canonicalMode = mode,
            gateSessionId = runtime.gateSessionId,
            outOfTimeLatchToken = runtime.outOfTimeLatchToken,
            pendingWaitId = pending?.id,
            activeUnlockId = state.activeUnlock?.id,
            settings = state.settings,
            budgetSnapshots = budgets,
            skipSnapshot = SkipSnapshot(
                enabled = state.settings.emergencySkipsEnabled,
                limit = state.settings.emergencySkipDailyLimit,
                used = state.dailyCounter.emergencySkipsUsed,
                remaining = remainingSkips,
                grantDurationMs = grantDuration,
                eligible = skipEligible,
            ),
            waitSnapshot = waitSnapshot,
            enforcementHealth = health(state, runtime),
            accessibilityEnabled = runtime.accessibilityEnabled,
            serviceConnected = runtime.serviceConnected,
            usageAccessGranted = runtime.usageAccessGranted,
            chromeInstalled = runtime.chromeInstalled,
            activeUnlock = state.activeUnlock,
            completedNormalUnlocksToday = state.dailyCounter.completedNormalUnlocks,
            latchedBlockers = runtime.latchedBlockers,
            wallNowMs = nowWallMs,
            isLoaded = isLoaded,
            lastErrorMessage = lastErrorMessage,
        )
    }

    fun startWait(
        input: PersistedState,
        runtime: RuntimeSignals,
        expectedRevision: Long,
        requestedMinutes: Long,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult {
        val state = normalize(input, nowWallMs, zoneId)
        if (state.stateRevision != expectedRevision) return rejected(state, "State changed")
        if (underlyingMode(state, runtime, nowWallMs) != GateMode.PAUSED) {
            return rejected(state, "Chrome is no longer paused")
        }
        if (requestedMinutes !in WaitCalculator.sessionMinutes) {
            return rejected(state, "Unsupported session duration")
        }
        val selectedDuration = safeMinutesToMs(requestedMinutes)
        val wait = PendingWait(
            id = idGenerator.nextId(),
            selectedDurationMs = selectedDuration,
            requiredFocusMs = WaitCalculator.requiredFocusMs(
                requestedMinutes,
                state.dailyCounter.completedNormalUnlocks,
            ),
            accumulatedFocusMs = 0,
            createdAtWallMs = nowWallMs,
            updatedAtWallMs = nowWallMs,
        )
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                pendingWait = wait,
                activeUnlock = null,
            ),
        )
    }

    fun advanceWait(
        input: PersistedState,
        runtime: RuntimeSignals,
        elapsedMs: Long,
        nowWallMs: Long,
        zoneId: ZoneId,
        expectedGateSessionId: String?,
    ): CommandResult {
        var state = normalize(input, nowWallMs, zoneId)
        val pending = state.pendingWait ?: return rejected(state, "No pending wait")
        if (elapsedMs <= 0) return CommandResult.Applied(state)
        val canAdvance = underlyingMode(state, runtime, nowWallMs) == GateMode.WAITING &&
            runtime.gateFocused && runtime.screenInteractive && runtime.keyguardUnlocked &&
            runtime.gateSessionId != null && runtime.gateSessionId == expectedGateSessionId
        if (!canAdvance) return rejected(state, "Wait is not focused")

        val accumulated = Math.addExact(pending.accumulatedFocusMs, elapsedMs)
            .coerceAtMost(pending.requiredFocusMs)
        state = state.copy(
            pendingWait = pending.copy(
                accumulatedFocusMs = accumulated,
                updatedAtWallMs = nowWallMs,
            ),
        )
        if (accumulated < pending.requiredFocusMs) return CommandResult.Applied(state)
        return completeNormalWait(state, runtime, nowWallMs, zoneId, pending.id, expectedGateSessionId)
    }

    fun completeNormalWait(
        input: PersistedState,
        runtime: RuntimeSignals,
        nowWallMs: Long,
        zoneId: ZoneId,
        expectedPendingWaitId: String,
        expectedGateSessionId: String?,
    ): CommandResult {
        val state = normalize(input, nowWallMs, zoneId)
        val pending = state.pendingWait ?: return rejected(state, "No pending wait")
        val valid = pending.id == expectedPendingWaitId &&
            pending.accumulatedFocusMs >= pending.requiredFocusMs &&
            runtime.gateSessionId != null && runtime.gateSessionId == expectedGateSessionId &&
            runtime.gateFocused &&
            underlyingMode(state, runtime, nowWallMs) == GateMode.WAITING &&
            state.settings.delayEnabled &&
            state.activeUnlock == null &&
            !budgetSnapshots(state).any { it.exhausted }
        if (!valid) return rejected(state, "Completion preconditions changed")

        val unlock = UnlockSession(
            id = idGenerator.nextId(),
            source = UnlockSource.NORMAL,
            grantedAtWallMs = nowWallMs,
            expiresAtWallMs = Math.addExact(nowWallMs, pending.selectedDurationMs),
        )
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                activeUnlock = unlock,
                pendingWait = null,
                dailyCounter = state.dailyCounter.copy(
                    completedNormalUnlocks = Math.addExact(
                        state.dailyCounter.completedNormalUnlocks,
                        1,
                    ),
                ),
            ),
        )
    }

    fun emergencySkip(
        input: PersistedState,
        runtime: RuntimeSignals,
        expectedRevision: Long,
        expectedPendingWaitId: String?,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult {
        var state = normalize(input, nowWallMs, zoneId)
        if (state.stateRevision != expectedRevision) return rejected(state, "State changed")
        if (state.pendingWait?.id != expectedPendingWaitId) return rejected(state, "Wait changed")

        state.pendingWait?.takeIf { it.accumulatedFocusMs >= it.requiredFocusMs }?.let { pending ->
            return when (
                val completion = completeNormalWait(
                    state,
                    runtime,
                    nowWallMs,
                    zoneId,
                    pending.id,
                    runtime.gateSessionId,
                )
            ) {
                is CommandResult.Applied -> CommandResult.NormalCompletionWon(completion.state)
                is CommandResult.NormalCompletionWon -> completion
                is CommandResult.Rejected -> completion
            }
        }

        val currentSnapshot = snapshot(state, runtime, nowWallMs)
        if (!currentSnapshot.skipSnapshot.eligible) return rejected(state, "Emergency skip unavailable")
        val duration = currentSnapshot.skipSnapshot.grantDurationMs
        val unlock = UnlockSession(
            id = idGenerator.nextId(),
            source = UnlockSource.EMERGENCY,
            grantedAtWallMs = nowWallMs,
            expiresAtWallMs = Math.addExact(nowWallMs, duration),
        )
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                activeUnlock = unlock,
                pendingWait = null,
                dailyCounter = state.dailyCounter.copy(
                    emergencySkipsUsed = Math.addExact(state.dailyCounter.emergencySkipsUsed, 1),
                ),
            ),
        )
    }

    fun consumeUsage(
        input: PersistedState,
        runtime: RuntimeSignals,
        intervalStartWallMs: Long,
        intervalEndWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState {
        if (intervalEndWallMs <= intervalStartWallMs) return input
        var state = normalize(input, intervalStartWallMs, zoneId)
        val mode = underlyingMode(state, runtime, intervalStartWallMs)
        if (mode !in accessGrantedModes) return state

        var cursor = intervalStartWallMs
        val originalExhausted = budgetSnapshots(state).any { it.exhausted }
        for (interval in PeriodCalculator.splitAtLocalMidnights(
            intervalStartWallMs,
            intervalEndWallMs,
            zoneId,
        )) {
            state = normalize(state, interval.startMs, zoneId)
            val budgets = budgetSnapshots(state)
            val maximum = budgets.filter { it.enabled }
                .minOfOrNull { it.remainingMs ?: Long.MAX_VALUE }
                ?: interval.durationMs
            val charged = minOf(interval.durationMs, maximum).coerceAtLeast(0)
            if (charged > 0) {
                state = state.copy(
                    dailyUsage = state.dailyUsage.copy(
                        usedMs = Math.addExact(state.dailyUsage.usedMs, charged),
                    ),
                    weeklyUsage = state.weeklyUsage.copy(
                        usedMs = Math.addExact(state.weeklyUsage.usedMs, charged),
                    ),
                    monthlyUsage = state.monthlyUsage.copy(
                        usedMs = Math.addExact(state.monthlyUsage.usedMs, charged),
                    ),
                    runtimeMetadata = state.runtimeMetadata.copy(
                        lastCommittedWallMs = interval.startMs + charged,
                    ),
                )
                cursor = interval.startMs + charged
            }
            if (charged < interval.durationMs) break
        }
        val nowExhausted = budgetSnapshots(state).any { it.exhausted }
        if (!originalExhausted && nowExhausted) {
            state = state.copy(stateRevision = nextRevision(state.stateRevision))
        }
        return state.copy(runtimeMetadata = state.runtimeMetadata.copy(lastCommittedWallMs = cursor))
    }

    fun saveSettings(
        input: PersistedState,
        newSettings: RestrictorSettings,
        expectedRevision: Long,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult {
        val state = normalize(input, nowWallMs, zoneId)
        if (state.stateRevision != expectedRevision) return rejected(state, "State changed")
        if (!validateSettings(newSettings)) return rejected(state, "Invalid settings")
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                settings = newSettings,
            ),
        )
    }

    fun resetUsage(input: PersistedState, nowWallMs: Long, zoneId: ZoneId): PersistedState {
        val state = normalize(input, nowWallMs, zoneId)
        val keys = PeriodCalculator.keysAt(nowWallMs, zoneId)
        return state.copy(
            stateRevision = nextRevision(state.stateRevision),
            dailyUsage = UsagePeriodState(BudgetPeriod.DAILY, keys.daily, 0),
            weeklyUsage = UsagePeriodState(BudgetPeriod.WEEKLY, keys.weekly, 0),
            monthlyUsage = UsagePeriodState(BudgetPeriod.MONTHLY, keys.monthly, 0),
            activeUnlock = null,
            pendingWait = null,
            dailyCounter = DailyCounter(localDayKey = keys.daily),
            runtimeMetadata = state.runtimeMetadata.copy(
                lastCommittedWallMs = nowWallMs,
                recoveryEpochId = idGenerator.nextId(),
            ),
        )
    }

    fun updateImmediateSettings(
        input: PersistedState,
        transform: (RestrictorSettings) -> RestrictorSettings,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState {
        val state = normalize(input, nowWallMs, zoneId)
        val next = transform(state.settings)
        require(validateSettings(next))
        if (next == state.settings) return state
        return state.copy(stateRevision = nextRevision(state.stateRevision), settings = next)
    }

    private fun health(state: PersistedState, runtime: RuntimeSignals): ProtectionHealth = when {
        runtime.storageError || runtime.storageWriteError -> ProtectionHealth.STORAGE_ERROR
        !runtime.chromeInstalled -> ProtectionHealth.CHROME_NOT_FOUND
        !state.settings.enforcementEnabled -> ProtectionHealth.ENFORCEMENT_OFF
        runtime.advancedProtectionEnabled && !runtime.accessibilityEnabled ->
            ProtectionHealth.ADVANCED_PROTECTION_BLOCKED
        !runtime.accessibilityEnabled -> ProtectionHealth.ACCESSIBILITY_REQUIRED
        !runtime.serviceConnected -> ProtectionHealth.SERVICE_DISCONNECTED
        runtime.usageRecoveryOperational && runtime.usageAccessGranted -> ProtectionHealth.READY
        else -> ProtectionHealth.READY_LIMITED_RECOVERY
    }

    private fun rejected(state: PersistedState, reason: String) = CommandResult.Rejected(state, reason)

    private fun safeMinutesToMs(minutes: Long): Long {
        require(minutes in 0..MAX_SAFE_MINUTES)
        return Math.multiplyExact(minutes, MINUTE_MS)
    }

    private fun nextRevision(revision: Long): Long = Math.addExact(revision, 1)

    companion object {
        val accessGrantedModes = setOf(
            GateMode.ACCESS_GRANTED_NORMAL,
            GateMode.ACCESS_GRANTED_EMERGENCY,
            GateMode.ACCESS_GRANTED_NO_DELAY,
        )
    }
}
