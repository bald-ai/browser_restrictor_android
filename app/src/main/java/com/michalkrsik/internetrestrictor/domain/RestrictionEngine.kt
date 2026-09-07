package com.michalkrsik.internetrestrictor.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

data class UsageConsumptionResult(
    val state: PersistedState,
    val recordedMs: Long,
    val chargedMs: Long,
)

class RestrictionEngine(
    private val idGenerator: IdGenerator = RandomIdGenerator,
) {
    fun validateSettings(settings: RestrictorSettings): Boolean =
        settings.enforcementEnabled &&
            settings.pauseUntilWallMs == null &&
            settings.delayEnabled &&
            settings.emergencySkipsEnabled &&
            settings.emergencySkipDailyLimit == EMERGENCY_SKIP_DAILY_LIMIT &&
            settings.sessionMinutes.size == DEFAULT_SESSION_MINUTES.size &&
            settings.sessionMinutes.all { it in MIN_SESSION_MINUTES..MAX_SESSION_MINUTES }

    fun normalize(input: PersistedState, nowWallMs: Long, zoneId: ZoneId): PersistedState {
        val keys = PeriodCalculator.keysAt(nowWallMs, zoneId)
        var state = input
        var revisionRelevant = false

        val canonicalSettings = state.settings.copy(
            enforcementEnabled = true,
            pauseUntilWallMs = null,
            delayEnabled = true,
            emergencySkipsEnabled = true,
            emergencySkipDailyLimit = EMERGENCY_SKIP_DAILY_LIMIT,
        )
        if (canonicalSettings != state.settings) {
            state = state.copy(settings = canonicalSettings)
            revisionRelevant = true
        }

        if (state.offUsageReminder != OffUsageReminderState() || state.currentVisitGraceActive) {
            state = state.copy(
                offUsageReminder = OffUsageReminderState(),
                currentVisitGraceActive = false,
            )
            revisionRelevant = true
        }

        val normalizedMonthlyCycles = MonthlyPushupCalculator.ensureCurrentCycle(
            state.monthlyPushupCycles,
            nowWallMs,
        )
        if (normalizedMonthlyCycles != state.monthlyPushupCycles) {
            state = state.copy(monthlyPushupCycles = normalizedMonthlyCycles)
            revisionRelevant = true
        }

        fun normalizedUsage(usage: UsagePeriodState, expectedKey: String): UsagePeriodState {
            if (usage.key == expectedKey) return usage
            revisionRelevant = true
            return usage.copy(key = expectedKey, usedMs = 0)
        }

        state = state.copy(
            dailyUsage = normalizedUsage(state.dailyUsage, keys.daily),
        )

        if (
            state.dailyCounter.localDayKey.isNotBlank() &&
            state.dailyCounter.earnedBudgetRounds > 0
        ) {
            val reportKey = state.dailyCounter.localDayKey
            val reports = state.usageReports.associateBy { it.localDayKey }.toMutableMap()
            val existing = reports[reportKey] ?: DailyUsageReport(reportKey)
            if (existing.exerciseSets != state.dailyCounter.earnedBudgetRounds) {
                reports[reportKey] = existing.copy(
                    offUsageMs = 0,
                    exerciseSets = state.dailyCounter.earnedBudgetRounds,
                )
                state = state.copy(usageReports = reports.values.sortedBy { it.localDayKey })
                revisionRelevant = true
            }
        }

        if (state.dailyCounter.localDayKey != keys.daily) {
            state = state.copy(dailyCounter = DailyCounter(localDayKey = keys.daily))
            revisionRelevant = true
        }

        val eveningResetWallMs = PeriodCalculator.eveningBudgetReset(nowWallMs, zoneId)
        if (nowWallMs >= eveningResetWallMs && !state.dailyCounter.eveningResetApplied) {
            state = state.copy(
                dailyUsage = state.dailyUsage.copy(usedMs = 0),
                dailyCounter = state.dailyCounter.copy(
                    spendableBudgetRounds = 0,
                    eveningResetApplied = true,
                ),
                activeUnlock = state.activeUnlock?.takeIf { it.source == UnlockSource.EMERGENCY },
                pendingWait = null,
            )
            revisionRelevant = true
        }

        // Fold old per-exercise surplus into the common pool once, after applying resets.
        val counter = state.dailyCounter
        val legacySixths = counter.pushupRepetitionRemainder * 6 +
            counter.plankSecondRemainder * 2 + counter.machineRepetitionRemainder * 3
        if (legacySixths > 0) {
            val total = counter.exercisePointSixths + legacySixths
            val boosts = total / EXERCISE_POINT_SIXTHS_PER_BOOST
            if (boosts <= MAX_SAFE_BUDGET_ROUNDS - counter.earnedBudgetRounds) {
                val earned = counter.earnedBudgetRounds + boosts
                state = state.copy(
                    dailyCounter = counter.copy(
                        exercisePointSixths = total % EXERCISE_POINT_SIXTHS_PER_BOOST,
                        pushupRepetitionRemainder = 0,
                        plankSecondRemainder = 0,
                        machineRepetitionRemainder = 0,
                        earnedBudgetRounds = earned,
                        spendableBudgetRounds = counter.spendableBudgetRounds + boosts,
                    ),
                    usageReports = state.usageReports.withExerciseSets(keys.daily, earned),
                )
                revisionRelevant = true
            }
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

        val todayKey = keys.daily
        val reportCutoff = LocalDate.parse(todayKey).minusDays(REPORT_HISTORY_DAYS.toLong()).toString()
        val retainedReports = state.usageReports
            .map { it.copy(offUsageMs = 0) }
            .filter { it.localDayKey >= reportCutoff }
            .sortedBy { it.localDayKey }
            .takeLast(MAX_REPORT_DAYS)
        if (retainedReports != state.usageReports) {
            state = state.copy(usageReports = retainedReports)
            revisionRelevant = true
        }

        val retainedEvents = state.activityEvents
            .filter { it.localDayKey >= reportCutoff }
            .sortedBy { it.startedAtWallMs }
            .takeLast(MAX_ACTIVITY_EVENTS)
        if (retainedEvents != state.activityEvents) {
            state = state.copy(activityEvents = retainedEvents)
            revisionRelevant = true
        }

        return if (revisionRelevant) state.copy(stateRevision = nextRevision(state.stateRevision)) else state
    }

    fun budgetSnapshots(state: PersistedState): List<BudgetSnapshot> {
        val usage = state.dailyUsage
        val minutes = Math.addExact(
            DAILY_BASE_BUDGET_MINUTES,
            Math.multiplyExact(
                state.dailyCounter.spendableBudgetRounds,
                EXERCISE_SET_REWARD_MINUTES,
            ),
        )
        val limitMs = safeMinutesToMs(minutes)
        val remaining = (limitMs - usage.usedMs).coerceAtLeast(0)
        val exhausted = remaining == 0L
        val percentage = when {
            limitMs == 0L -> 100
            else -> ((usage.usedMs.coerceAtMost(limitMs).toDouble() / limitMs) * 100).roundToInt()
        }
        return listOf(BudgetSnapshot(
            period = BudgetPeriod.DAILY,
            enabled = true,
            limitMinutes = minutes,
            usedMs = usage.usedMs,
            remainingMs = remaining,
            percentage = percentage,
            exhausted = exhausted,
        ))
    }

    fun underlyingMode(state: PersistedState, runtime: RuntimeSignals, nowWallMs: Long): GateMode {
        if (runtime.storageError) return GateMode.STORAGE_ERROR
        if (runtime.activeTarget == null || !runtime.screenInteractive || !runtime.keyguardUnlocked) {
            return GateMode.BROWSER_BACKGROUND
        }
        if (!runtime.storageReady || !runtime.accessibilityEnabled || !runtime.serviceConnected) {
            return GateMode.SERVICE_UNAVAILABLE
        }
        if (runtime.activeTarget == ProtectedTarget.YOUTUBE) {
            return if (state.dailyCounter.earnedBudgetRounds >= YOUTUBE_UNLOCK_SETS) {
                GateMode.YOUTUBE_ACCESS_GRANTED
            } else {
                GateMode.YOUTUBE_LOCKED
            }
        }
        state.activeUnlock?.takeIf { it.expiresAtWallMs > nowWallMs }?.let {
            when (it.source) {
                UnlockSource.EMERGENCY -> return GateMode.ACCESS_GRANTED_EMERGENCY
                UnlockSource.NORMAL -> Unit
            }
        }
        if (budgetSnapshots(state).any { it.exhausted }) return GateMode.OUT_OF_TIME
        state.activeUnlock?.takeIf { it.expiresAtWallMs > nowWallMs }?.let {
            return GateMode.ACCESS_GRANTED_NORMAL
        }
        if (state.pendingWait != null) return GateMode.WAITING
        return GateMode.PAUSED
    }

    fun snapshot(
        state: PersistedState,
        runtime: RuntimeSignals,
        nowWallMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        isLoaded: Boolean = true,
        lastErrorMessage: String? = null,
    ): StateSnapshot {
        val budgets = budgetSnapshots(state)
        val underlying = underlyingMode(state, runtime, nowWallMs)
        val mode = if (
            underlying != GateMode.OUT_OF_TIME &&
            runtime.outOfTimeLatchToken != null &&
            runtime.activeTarget == ProtectedTarget.BROWSER_POOL &&
            runtime.serviceConnected
        ) {
            GateMode.OUT_OF_TIME_LATCHED
        } else {
            underlying
        }
        val pending = state.pendingWait
        val currentMonthlyCycleKey = MonthlyPushupCalculator.cycleStartKey(nowWallMs)
        val currentMonthlyCycle = state.monthlyPushupCycles.firstOrNull {
            it.cycleStartDate == currentMonthlyCycleKey
        } ?: MonthlyPushupCycle(currentMonthlyCycleKey)
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
        val emergencyEarnedToday = state.dailyCounter.earnedBudgetRounds > 0
        val remainingSkips = if (emergencyEarnedToday) {
            (EMERGENCY_SKIP_DAILY_LIMIT - state.dailyCounter.emergencySkipsUsed).coerceAtLeast(0)
        } else {
            0
        }
        val grantDuration = EMERGENCY_UNLOCK_MS
        val skipModeEligible = when (mode) {
            GateMode.PAUSED, GateMode.WAITING -> true
            GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> true
            else -> false
        }
        val activeUnlockIsCurrent = state.activeUnlock?.expiresAtWallMs?.let { it > nowWallMs } == true
        val skipEligible = emergencyEarnedToday &&
            remainingSkips > 0 &&
            skipModeEligible &&
            !activeUnlockIsCurrent &&
            grantDuration > 0

        return StateSnapshot(
            stateRevision = state.stateRevision,
            canonicalMode = mode,
            gateSessionId = runtime.gateSessionId,
            outOfTimeLatchToken = runtime.outOfTimeLatchToken,
            pendingWaitId = pending?.id,
            activeUnlockId = state.activeUnlock?.id,
            activeTarget = runtime.activeTarget,
            settings = state.settings,
            budgetSnapshots = budgets,
            skipSnapshot = SkipSnapshot(
                enabled = true,
                limit = EMERGENCY_SKIP_DAILY_LIMIT,
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
            supportedBrowserInstalled = runtime.supportedBrowserInstalled,
            activeUnlock = state.activeUnlock,
            earnedBudgetRoundsToday = state.dailyCounter.earnedBudgetRounds,
            exercisePointSixthsToday = state.dailyCounter.exercisePointSixths,
            verifiedPushupsToday = state.dailyCounter.verifiedPushups,
            offSessionStartedAtWallMs = state.offUsageReminder.sessionStartedAtWallMs,
            usageReports = state.usageReports,
            todayUsageReport = state.usageReports.firstOrNull {
                it.localDayKey == PeriodCalculator.keysAt(nowWallMs, zoneId).daily
            } ?: DailyUsageReport(PeriodCalculator.keysAt(nowWallMs, zoneId).daily),
            currentWeekUsageReports = currentWeekReports(state, nowWallMs, zoneId),
            currentMonthlyPushupCycle = currentMonthlyCycle,
            completedMonthlyPushupCycles = state.monthlyPushupCycles
                .filter { it.cycleStartDate < currentMonthlyCycleKey }
                .sortedByDescending { it.cycleStartDate },
            todayActivityEvents = state.activityEvents.filter {
                it.localDayKey == PeriodCalculator.keysAt(nowWallMs, zoneId).daily
            },
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
            return rejected(state, "The browser is no longer paused")
        }
        if (requestedMinutes !in state.settings.sessionMinutes) {
            return rejected(state, "Unsupported session duration")
        }
        val selectedDuration = safeMinutesToMs(requestedMinutes)
        val wait = PendingWait(
            id = idGenerator.nextId(),
            selectedDurationMs = selectedDuration,
            requiredFocusMs = WaitCalculator.requiredFocusMs(requestedMinutes),
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

    fun earnPushupBudget(
        input: PersistedState,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult = recordVerifiedExercise(
        input = input,
        exerciseId = ExerciseId.PUSH_UP,
        value = PUSHUPS_PER_SET,
        nowWallMs = nowWallMs,
        zoneId = zoneId,
    )

    fun recordPushupRepetitions(
        input: PersistedState,
        repetitions: Long,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult = recordVerifiedExercise(
        input = input,
        exerciseId = ExerciseId.PUSH_UP,
        value = repetitions,
        nowWallMs = nowWallMs,
        zoneId = zoneId,
    )

    fun recordVerifiedExercise(
        input: PersistedState,
        exerciseId: ExerciseId,
        value: Long,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult = recordVerifiedExercise(
        input = input,
        verificationId = idGenerator.nextId(),
        exerciseId = exerciseId,
        value = value,
        nowWallMs = nowWallMs,
        zoneId = zoneId,
    )

    fun recordVerifiedExercise(
        input: PersistedState,
        verificationId: String,
        exerciseId: ExerciseId,
        value: Long,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult {
        val state = normalize(input, nowWallMs, zoneId)
        if (verificationId.isBlank() || verificationId.length > 128) {
            return rejected(state, "Invalid exercise verification ID")
        }
        if (verificationId in state.dailyCounter.creditedExerciseVerificationIds) {
            return rejected(state, "Exercise verification was already credited")
        }
        if (
            state.dailyCounter.creditedExerciseVerificationIds.size >=
            MAX_DAILY_CREDITED_EXERCISE_VERIFICATIONS
        ) {
            return rejected(state, "Daily exercise verification history is at its safe limit")
        }
        val policy = ExercisePolicies.find(exerciseId)
        if (policy == null || !policy.credited) {
            return rejected(state, "Unsupported exercise cannot earn credit")
        }
        if (value <= 0) return rejected(state, "No countable ${policy.displayName.lowercase()} were measured")
        val accumulation = try {
            accumulateExerciseUnits(
                previousRemainder = state.dailyCounter.exercisePointSixths,
                added = Math.multiplyExact(value, exercisePointSixthsPerUnit(exerciseId)),
                unitsPerSet = EXERCISE_POINT_SIXTHS_PER_BOOST,
            )
        } catch (_: ArithmeticException) {
            return rejected(state, "${policy.displayName} measurement is too large")
        }
        val newlyEarnedRounds = accumulation.completedSets
        if (newlyEarnedRounds > MAX_SAFE_BUDGET_ROUNDS - state.dailyCounter.earnedBudgetRounds) {
            return rejected(state, "Daily earned budget is at its safe limit")
        }
        if (newlyEarnedRounds > MAX_SAFE_BUDGET_ROUNDS - state.dailyCounter.spendableBudgetRounds) {
            return rejected(state, "Spendable budget is at its safe limit")
        }
        val totalEarnedRounds = Math.addExact(
            state.dailyCounter.earnedBudgetRounds,
            newlyEarnedRounds,
        )
        val spendableRounds = Math.addExact(
            state.dailyCounter.spendableBudgetRounds,
            newlyEarnedRounds,
        )
        val monthlyPushupCycles = if (exerciseId == ExerciseId.PUSH_UP) {
            try {
                MonthlyPushupCalculator.addPushups(
                    cycles = state.monthlyPushupCycles,
                    repetitions = value,
                    nowWallMs = nowWallMs,
                )
            } catch (_: ArithmeticException) {
                return rejected(state, "Monthly push-up total is at its safe limit")
            }
        } else {
            state.monthlyPushupCycles
        }
        val verifiedPushups = if (exerciseId == ExerciseId.PUSH_UP) {
            try {
                Math.addExact(state.dailyCounter.verifiedPushups, value)
            } catch (_: ArithmeticException) {
                return rejected(state, "Daily push-up total is at its safe limit")
            }
        } else {
            state.dailyCounter.verifiedPushups
        }
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                dailyCounter = state.dailyCounter.copy(
                    exercisePointSixths = accumulation.remainder,
                    earnedBudgetRounds = totalEarnedRounds,
                    spendableBudgetRounds = spendableRounds,
                    verifiedPushups = verifiedPushups,
                    creditedExerciseVerificationIds =
                        state.dailyCounter.creditedExerciseVerificationIds + verificationId,
                ),
                usageReports = state.usageReports.withExerciseSets(
                    localDayKey = state.dailyCounter.localDayKey,
                    sessions = totalEarnedRounds,
                ),
                monthlyPushupCycles = monthlyPushupCycles,
                activityEvents = if (newlyEarnedRounds > 0) {
                    (state.activityEvents + ActivityEvent(
                        id = idGenerator.nextId(),
                        localDayKey = state.dailyCounter.localDayKey,
                        type = ActivityEventType.EXERCISE_SET,
                        startedAtWallMs = nowWallMs,
                        endedAtWallMs = nowWallMs,
                        valueMs = newlyEarnedRounds * EXERCISE_SET_REWARD_MINUTES * MINUTE_MS,
                    )).takeLast(MAX_ACTIVITY_EVENTS)
                } else {
                    state.activityEvents
                },
            ),
        )
    }

    fun consumeUsageDetailed(
        input: PersistedState,
        runtime: RuntimeSignals,
        intervalStartWallMs: Long,
        intervalEndWallMs: Long,
        zoneId: ZoneId,
    ): UsageConsumptionResult {
        if (intervalEndWallMs <= intervalStartWallMs) return UsageConsumptionResult(input, 0L, 0L)
        var state = normalize(input, intervalStartWallMs, zoneId)
        val initialMode = underlyingMode(state, runtime, intervalStartWallMs)
        if (initialMode !in accessGrantedModes) return UsageConsumptionResult(state, 0L, 0L)

        var cursor = intervalStartWallMs
        var totalRecordedMs = 0L
        var totalChargedMs = 0L
        val originalExhausted = budgetSnapshots(state).any { it.exhausted }
        while (cursor < intervalEndWallMs) {
            state = normalize(state, cursor, zoneId)
            val mode = underlyingMode(state, runtime, cursor)
            if (mode !in accessGrantedModes) break

            val eveningReset = PeriodCalculator.eveningBudgetReset(cursor, zoneId)
            val dailyReset = PeriodCalculator.nextDailyReset(cursor, zoneId)
            val resetBoundary = if (cursor < eveningReset) minOf(eveningReset, dailyReset) else dailyReset
            val unlockBoundary = state.activeUnlock?.expiresAtWallMs?.takeIf { it > cursor }
            val nextBoundary = listOfNotNull(resetBoundary, unlockBoundary).minOrNull() ?: intervalEndWallMs
            val segmentEnd = minOf(intervalEndWallMs, nextBoundary)
            val segmentDuration = segmentEnd - cursor
            if (segmentDuration <= 0) break

            val budgets = budgetSnapshots(state)
            val isEmergency = state.activeUnlock?.let {
                it.source == UnlockSource.EMERGENCY && it.expiresAtWallMs > cursor
            } == true

            val accessible: Long
            val budgetCharged: Long

            if (isEmergency) {
                accessible = segmentDuration
                budgetCharged = 0L
            } else {
                val budgetRemaining = budgets.filter { it.enabled }
                    .minOfOrNull { it.remainingMs ?: Long.MAX_VALUE }
                    ?: segmentDuration
                accessible = minOf(segmentDuration, budgetRemaining).coerceAtLeast(0)
                budgetCharged = accessible
            }

            if (accessible > 0) {
                state = state.copy(
                    dailyUsage = state.dailyUsage.copy(
                        usedMs = Math.addExact(state.dailyUsage.usedMs, budgetCharged),
                    ),
                    runtimeMetadata = state.runtimeMetadata.copy(
                        lastCommittedWallMs = cursor + accessible,
                    ),
                )
                state = addReportInterval(
                    input = state,
                    intervalStartWallMs = cursor,
                    intervalEndWallMs = cursor + accessible,
                    zoneId = zoneId,
                )
                cursor += accessible
                totalRecordedMs = Math.addExact(totalRecordedMs, accessible)
                totalChargedMs = Math.addExact(totalChargedMs, budgetCharged)
            }

            if (accessible < segmentDuration) break
        }
        val nowExhausted = budgetSnapshots(state).any { it.exhausted }
        if (!originalExhausted && nowExhausted) {
            state = state.copy(stateRevision = nextRevision(state.stateRevision))
        }
        val finalState = state.copy(runtimeMetadata = state.runtimeMetadata.copy(lastCommittedWallMs = cursor))
        return UsageConsumptionResult(
            state = finalState,
            recordedMs = totalRecordedMs,
            chargedMs = totalChargedMs,
        )
    }

    fun consumeUsage(
        input: PersistedState,
        runtime: RuntimeSignals,
        intervalStartWallMs: Long,
        intervalEndWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState = consumeUsageDetailed(
        input = input,
        runtime = runtime,
        intervalStartWallMs = intervalStartWallMs,
        intervalEndWallMs = intervalEndWallMs,
        zoneId = zoneId,
    ).state

    fun clearReportHistory(input: PersistedState): PersistedState = input.copy(
        stateRevision = nextRevision(input.stateRevision),
        usageReports = emptyList(),
        activityEvents = emptyList(),
    )

    fun cancelActiveUnlock(
        input: PersistedState,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState {
        val state = normalize(input, nowWallMs, zoneId)
        if (state.activeUnlock == null) return state
        return state.copy(
            stateRevision = nextRevision(state.stateRevision),
            activeUnlock = null,
        )
    }

    private fun addReportInterval(
        input: PersistedState,
        intervalStartWallMs: Long,
        intervalEndWallMs: Long,
        zoneId: ZoneId,
    ): PersistedState {
        if (intervalEndWallMs <= intervalStartWallMs) return input
        var state = input
        val reports = state.usageReports.associateBy { it.localDayKey }.toMutableMap()
        for (interval in PeriodCalculator.splitAtDailyResets(
            intervalStartWallMs,
            intervalEndWallMs,
            zoneId,
        )) {
            val key = PeriodCalculator.keysAt(interval.startMs, zoneId).daily
            val current = reports[key] ?: DailyUsageReport(key)
            reports[key] = current.copy(
                offUsageMs = 0,
                protectedUsageMs = Math.addExact(current.protectedUsageMs, interval.durationMs),
            )
            state = state.copy(
                activityEvents = state.activityEvents.withBrowserUsageEvent(
                    localDayKey = key,
                    startedAtWallMs = interval.startMs,
                    endedAtWallMs = interval.endMs,
                ),
            )
        }
        val cutoff = LocalDate.parse(PeriodCalculator.keysAt(intervalEndWallMs - 1, zoneId).daily)
            .minusDays(REPORT_HISTORY_DAYS.toLong())
            .toString()
        return state.copy(
            usageReports = reports.values
                .filter { it.localDayKey >= cutoff }
                .sortedBy { it.localDayKey }
                .takeLast(MAX_REPORT_DAYS),
        )
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
        val canonicalSettings = newSettings.copy(
            enforcementEnabled = true,
            pauseUntilWallMs = null,
            delayEnabled = true,
            emergencySkipsEnabled = true,
            emergencySkipDailyLimit = EMERGENCY_SKIP_DAILY_LIMIT,
        )
        if (!validateSettings(canonicalSettings)) return rejected(state, "Invalid settings")
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                settings = canonicalSettings,
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
        !runtime.supportedBrowserInstalled -> ProtectionHealth.BROWSERS_NOT_FOUND
        runtime.advancedProtectionEnabled && !runtime.accessibilityEnabled ->
            ProtectionHealth.ADVANCED_PROTECTION_BLOCKED
        !runtime.accessibilityEnabled -> ProtectionHealth.ACCESSIBILITY_REQUIRED
        !runtime.serviceConnected -> ProtectionHealth.SERVICE_DISCONNECTED
        runtime.usageRecoveryOperational && runtime.usageAccessGranted -> ProtectionHealth.READY
        else -> ProtectionHealth.READY_LIMITED_RECOVERY
    }

    private fun currentWeekReports(
        state: PersistedState,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): List<DailyUsageReport> {
        val today = LocalDate.parse(PeriodCalculator.keysAt(nowWallMs, zoneId).daily)
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val reports = state.usageReports.associateBy { it.localDayKey }
        return (0L..6L).map { offset ->
            val key = monday.plusDays(offset).toString()
            reports[key] ?: DailyUsageReport(key)
        }
    }

    private fun List<DailyUsageReport>.withExerciseSets(
        localDayKey: String,
        sessions: Long,
    ): List<DailyUsageReport> {
        val reports = associateBy { it.localDayKey }.toMutableMap()
        val current = reports[localDayKey] ?: DailyUsageReport(localDayKey)
        reports[localDayKey] = current.copy(offUsageMs = 0, exerciseSets = sessions)
        return reports.values.sortedBy { it.localDayKey }.takeLast(MAX_REPORT_DAYS)
    }

    private fun List<ActivityEvent>.withBrowserUsageEvent(
        localDayKey: String,
        startedAtWallMs: Long,
        endedAtWallMs: Long,
    ): List<ActivityEvent> {
        val durationMs = endedAtWallMs - startedAtWallMs
        if (durationMs <= 0) return this
        val events = toMutableList()
        val last = events.lastOrNull()
        if (
            last?.type == ActivityEventType.BROWSER_SESSION &&
            last.localDayKey == localDayKey &&
            startedAtWallMs >= last.endedAtWallMs &&
            startedAtWallMs - last.endedAtWallMs <= BROWSER_EVENT_MERGE_GAP_MS
        ) {
            events[events.lastIndex] = last.copy(
                endedAtWallMs = endedAtWallMs,
                valueMs = Math.addExact(last.valueMs, durationMs),
            )
        } else {
            events += ActivityEvent(
                id = idGenerator.nextId(),
                localDayKey = localDayKey,
                type = ActivityEventType.BROWSER_SESSION,
                startedAtWallMs = startedAtWallMs,
                endedAtWallMs = endedAtWallMs,
                valueMs = durationMs,
            )
        }
        return events.takeLast(MAX_ACTIVITY_EVENTS)
    }

    private fun rejected(state: PersistedState, reason: String) = CommandResult.Rejected(state, reason)

    private fun safeMinutesToMs(minutes: Long): Long {
        require(minutes in 0..MAX_SAFE_MINUTES)
        return Math.multiplyExact(minutes, MINUTE_MS)
    }

    private fun nextRevision(revision: Long): Long = Math.addExact(revision, 1)

    companion object {
        const val REPORT_HISTORY_DAYS = 30
        const val MAX_REPORT_DAYS = REPORT_HISTORY_DAYS + 1
        const val MAX_ACTIVITY_EVENTS = 512
        private const val BROWSER_EVENT_MERGE_GAP_MS = 10_000L
        val accessGrantedModes = setOf(
            GateMode.ACCESS_GRANTED_NORMAL,
            GateMode.ACCESS_GRANTED_EMERGENCY,
            GateMode.ACCESS_GRANTED_NO_DELAY,
        )
    }
}
