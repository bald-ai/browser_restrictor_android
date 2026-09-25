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
            settings.sessionMinutes.size in 1..6 &&
            settings.sessionMinutes.all { it in MIN_SESSION_MINUTES..MAX_SESSION_MINUTES }

    fun normalize(input: PersistedState, nowWallMs: Long, zoneId: ZoneId): PersistedState {
        val dayKey = PeriodCalculator.keysAt(nowWallMs, zoneId, input.configuration.settings).daily
        var state = input
        var revisionRelevant = false
        // Track each transition, including an old report restored and then pruned below.
        fun accept(next: PersistedState) {
            if (next != state) revisionRelevant = true
            state = next
        }
        accept(canonicalProtectionSettings(state))
        accept(state.copy(monthlyPushupCycles = MonthlyPushupCalculator.ensureCurrentCycle(state.monthlyPushupCycles, nowWallMs, zoneId)))
        accept(state.copy(dailyUsage = if (state.dailyUsage.key == dayKey) state.dailyUsage else state.dailyUsage.copy(key = dayKey, usedMs = 0)))
        accept(restoreExerciseReport(state))
        if (state.lastDailyRewardResetWallMs == 0L) revisionRelevant = true
        accept(normalizeDailyCounter(state, dayKey, nowWallMs, zoneId))
        accept(migrateLegacyExercisePoints(state, dayKey))
        accept(expireAccess(state, nowWallMs))
        accept(pruneHistory(state, dayKey))
        return if (revisionRelevant) state.copy(stateRevision = nextRevision(state.stateRevision)) else state
    }

    private fun canonicalProtectionSettings(state: PersistedState): PersistedState = state.copy(
        settings = state.settings.copy(
            enforcementEnabled = true, pauseUntilWallMs = null, delayEnabled = true,
            emergencySkipsEnabled = true, emergencySkipDailyLimit = EMERGENCY_SKIP_DAILY_LIMIT,
        ),
        offUsageReminder = OffUsageReminderState(), currentVisitGraceActive = false,
    )

    private fun restoreExerciseReport(state: PersistedState): PersistedState {
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
                return state.copy(usageReports = reports.values.sortedBy { it.localDayKey })
            }
        }

        return state
    }

    private fun normalizeDailyCounter(input: PersistedState, dayKey: String, nowWallMs: Long, zoneId: ZoneId): PersistedState {
        var state = input
        val configSettings = state.configuration.settings
        if (state.dailyCounter.localDayKey != dayKey) {
            state = state.copy(
                dailyCounter = DailyCounter(localDayKey = dayKey,
                    grantedBudgetMinutes = configSettings.dailyBudgetMinutes.takeIf { state.dailyCounter.grantedBudgetMinutes != null || it > 0 }),
                taskCreditProgress = CreditFraction(), taskProgressBase = CreditFraction(), taskBudgetKey = "",
                lastRewardResetWallMs = if (state.dailyCounter.localDayKey.isNotBlank())
                    maxOf(state.lastRewardResetWallMs, PeriodCalculator.dailyResetStart(nowWallMs, zoneId, configSettings))
                    else state.lastRewardResetWallMs,
                lastDailyRewardResetWallMs = maxOf(state.lastDailyRewardResetWallMs,
                    PeriodCalculator.dailyResetStart(nowWallMs, zoneId, configSettings)),
            )
        }
        if (state.lastDailyRewardResetWallMs == 0L) {
            state = state.copy(lastDailyRewardResetWallMs = PeriodCalculator.dailyResetStart(nowWallMs, zoneId, configSettings))
        }

        return state
    }

    /** Fold old per-exercise surplus into the common pool once, after applying resets. */
    private fun migrateLegacyExercisePoints(state: PersistedState, dayKey: String): PersistedState {
        val counter = state.dailyCounter
        val legacySixths = counter.pushupRepetitionRemainder * 6 +
            counter.plankSecondRemainder * 2 + counter.machineRepetitionRemainder * 3
        if (legacySixths > 0) {
            val total = counter.exercisePointSixths + legacySixths
            val boosts = total / EXERCISE_POINT_SIXTHS_PER_BOOST
            if (boosts <= MAX_SAFE_BUDGET_ROUNDS - counter.earnedBudgetRounds) {
                val earned = counter.earnedBudgetRounds + boosts
                return state.copy(
                    dailyCounter = counter.copy(
                        exercisePointSixths = total % EXERCISE_POINT_SIXTHS_PER_BOOST,
                        pushupRepetitionRemainder = 0,
                        plankSecondRemainder = 0,
                        machineRepetitionRemainder = 0,
                        earnedBudgetRounds = earned,
                        spendableBudgetRounds = counter.spendableBudgetRounds + boosts,
                    ),
                    usageReports = state.usageReports.withExerciseSets(dayKey, earned),
                )
            }
        }
        return state
    }

    private fun expireAccess(input: PersistedState, nowWallMs: Long): PersistedState {
        var state = input
        state.activeUnlock?.let { unlock ->
            if (unlock.expiresAtWallMs <= nowWallMs) {
                state = state.copy(activeUnlock = null)
            }
        }

        state.pendingWait?.let { pending ->
            val expired = nowWallMs < pending.createdAtWallMs ||
                nowWallMs - pending.createdAtWallMs >= PENDING_WAIT_EXPIRY_MS
            if (expired) {
                state = state.copy(pendingWait = null)
            }
        }

        return state
    }

    private fun pruneHistory(input: PersistedState, dayKey: String): PersistedState {
        var state = input
        val todayKey = dayKey
        val reportCutoff = LocalDate.parse(todayKey).minusDays(REPORT_HISTORY_DAYS.toLong()).toString()
        val retainedReports = state.usageReports
            .map { it.copy(offUsageMs = 0) }
            .filter { it.localDayKey >= reportCutoff }
            .sortedBy { it.localDayKey }
            .takeLast(MAX_REPORT_DAYS)
        if (retainedReports != state.usageReports) {
            state = state.copy(usageReports = retainedReports)
        }

        val retainedEvents = state.activityEvents
            .filter { it.localDayKey >= reportCutoff }
            .sortedBy { it.startedAtWallMs }
            .takeLast(MAX_ACTIVITY_EVENTS)
        if (retainedEvents != state.activityEvents) {
            state = state.copy(activityEvents = retainedEvents)
        }

        return state
    }

    fun budgetSnapshots(state: PersistedState): List<BudgetSnapshot> {
        val usage = state.dailyUsage
        val minutes = state.dailyCounter.grantedBudgetMinutes ?: Math.addExact(
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

    private fun unavailableMode(runtime: RuntimeSignals): GateMode? {
        if (runtime.storageError) return GateMode.STORAGE_ERROR
        if (runtime.activeTarget == null || !runtime.screenInteractive || !runtime.keyguardUnlocked) {
            return GateMode.BROWSER_BACKGROUND
        }
        if (!runtime.storageReady || !runtime.accessibilityEnabled || !runtime.serviceConnected) {
            return GateMode.SERVICE_UNAVAILABLE
        }
        return null
    }

    fun underlyingMode(state: PersistedState, runtime: RuntimeSignals, nowWallMs: Long): GateMode {
        unavailableMode(runtime)?.let { return it }
        if (runtime.activeTarget == ProtectedTarget.YOUTUBE) {
            return if (state.dailyCounter.earnedBudgetRounds >= state.configuration.settings.youtubeCredits) {
                GateMode.YOUTUBE_ACCESS_GRANTED
            } else {
                GateMode.YOUTUBE_LOCKED
            }
        }
        return browserMode(state, nowWallMs)
    }

    private fun browserMode(state: PersistedState, nowWallMs: Long): GateMode {
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
        val mode = snapshotMode(underlying, runtime)
        val pending = state.pendingWait
        val currentMonthlyCycleKey = MonthlyPushupCalculator.cycleStartKey(nowWallMs, zoneId)
        val currentMonthlyCycle = state.monthlyPushupCycles.firstOrNull {
            it.cycleStartDate == currentMonthlyCycleKey
        } ?: MonthlyPushupCycle(currentMonthlyCycleKey)
        val waitSnapshot = waitSnapshot(pending)

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
            skipSnapshot = skipSnapshot(state, mode, budgets, nowWallMs),
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
            sprintMetersRemainder = state.dailyCounter.sprintMetersRemainder,
            offSessionStartedAtWallMs = state.offUsageReminder.sessionStartedAtWallMs,
            usageReports = state.usageReports,
            todayUsageReport = state.usageReports.firstOrNull {
                it.localDayKey == PeriodCalculator.keysAt(nowWallMs, zoneId, state.configuration.settings).daily
            } ?: DailyUsageReport(PeriodCalculator.keysAt(nowWallMs, zoneId, state.configuration.settings).daily),
            currentWeekUsageReports = currentWeekReports(state, nowWallMs, zoneId),
            currentMonthlyPushupCycle = currentMonthlyCycle,
            completedMonthlyPushupCycles = state.monthlyPushupCycles
                .filter { it.cycleStartDate < currentMonthlyCycleKey }
                .sortedByDescending { it.cycleStartDate },
            todayActivityEvents = state.activityEvents.filter {
                it.localDayKey == PeriodCalculator.keysAt(nowWallMs, zoneId, state.configuration.settings).daily
            },
            latchedBlockers = runtime.latchedBlockers,
            wallNowMs = nowWallMs,
            isLoaded = isLoaded,
            lastErrorMessage = lastErrorMessage,
            lastExercise = state.lastExercise,
            canCorrectLastExercise = state.lastExercise?.canCorrect(state.dailyCounter) == true,
            configuration = state.configuration,
            hasAppliedServerConfiguration = state.hasAppliedServerConfiguration,
            taskReceipts = state.taskReceipts,
            taskCreditProgress = if (state.taskBudgetKey.isNotBlank()) state.taskCreditProgress.approximate else
                state.dailyCounter.exercisePointSixths.toDouble() / EXERCISE_POINT_SIXTHS_PER_BOOST,
            recentActivityEvents = state.activityEvents.filter { it.endedAtWallMs >= nowWallMs - 14 * DAY_MS },
            recentCorrectionEvents = state.correctionHistory.filter { it.correctedAtWallMs >= nowWallMs - 14 * DAY_MS },
            lastRewardResetWallMs = state.lastRewardResetWallMs,
            lastDailyRewardResetWallMs = state.lastDailyRewardResetWallMs,
            modifiableTaskReceiptIds = state.taskReceipts.filter {
                it.canModifyReward(state.taskBudgetKey, nowWallMs)
            }.mapTo(mutableSetOf()) { it.measurement.id },
        )
    }

    private fun snapshotMode(underlying: GateMode, runtime: RuntimeSignals): GateMode {
        return if (
            underlying != GateMode.OUT_OF_TIME &&
            runtime.outOfTimeLatchToken != null &&
            runtime.activeTarget == ProtectedTarget.BROWSER_POOL &&
            runtime.serviceConnected
        ) {
            GateMode.OUT_OF_TIME_LATCHED
        } else {
            underlying
        }
    }

    private fun waitSnapshot(pending: PendingWait?): WaitSnapshot? {
        return pending?.let {
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
    }

    private fun skipSnapshot(state: PersistedState, mode: GateMode, budgets: List<BudgetSnapshot>, nowWallMs: Long): SkipSnapshot {
        val emergencyEarnedToday = state.dailyCounter.earnedBudgetRounds > 0
        val remainingSkips = if (emergencyEarnedToday) {
            (EMERGENCY_SKIP_DAILY_LIMIT - state.dailyCounter.emergencySkipsUsed).coerceAtLeast(0)
        } else {
            0
        }
        val grantDuration = state.configuration.settings.emergencyMinutes * MINUTE_MS
        val skipEligible = eligibleForEmergency(state, mode, budgets, nowWallMs, remainingSkips, grantDuration)
        return SkipSnapshot(enabled = true, limit = EMERGENCY_SKIP_DAILY_LIMIT,
            used = state.dailyCounter.emergencySkipsUsed, remaining = remainingSkips,
            grantDurationMs = grantDuration, eligible = skipEligible)
    }

    private fun eligibleForEmergency(state: PersistedState, mode: GateMode, budgets: List<BudgetSnapshot>,
        nowWallMs: Long, remainingSkips: Long, grantDuration: Long): Boolean {
        val skipModeEligible = mode in setOf(GateMode.PAUSED, GateMode.WAITING, GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED)
        val activeUnlockIsCurrent = state.activeUnlock?.let {
            it.expiresAtWallMs > nowWallMs &&
                (it.source == UnlockSource.EMERGENCY || budgets.none { budget -> budget.exhausted })
        } == true
        return state.dailyCounter.earnedBudgetRounds > 0 &&
            remainingSkips > 0 &&
            skipModeEligible &&
            !activeUnlockIsCurrent &&
            grantDuration > 0
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
            requiredFocusMs = state.configuration.settings.waitSeconds * 1000,
            accumulatedFocusMs = 0,
            createdAtWallMs = nowWallMs,
            updatedAtWallMs = nowWallMs,
        )
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                pendingWait = wait.takeIf { it.requiredFocusMs > 0 },
                activeUnlock = if (wait.requiredFocusMs == 0L) UnlockSession(idGenerator.nextId(),
                    UnlockSource.NORMAL, nowWallMs, Math.addExact(nowWallMs, selectedDuration)) else null,
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
        val canAdvance = canAdvanceFocusedWait(state, runtime, nowWallMs, expectedGateSessionId)
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

    private fun canAdvanceFocusedWait(state: PersistedState, runtime: RuntimeSignals,
        nowWallMs: Long, expectedGateSessionId: String?): Boolean {
        return underlyingMode(state, runtime, nowWallMs) == GateMode.WAITING &&
            runtime.gateFocused && runtime.gateSessionId != null && runtime.gateSessionId == expectedGateSessionId
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
        val valid = completedWaitIsCurrent(state, runtime, pending, nowWallMs, expectedPendingWaitId, expectedGateSessionId)
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

    private fun completedWaitIsCurrent(state: PersistedState, runtime: RuntimeSignals, pending: PendingWait,
        nowWallMs: Long, expectedPendingWaitId: String, expectedGateSessionId: String?): Boolean {
        return pending.id == expectedPendingWaitId &&
            pending.accumulatedFocusMs >= pending.requiredFocusMs &&
            runtime.focusedGateMatches(expectedGateSessionId) &&
            underlyingMode(state, runtime, nowWallMs) == GateMode.WAITING
    }

    private fun RuntimeSignals.focusedGateMatches(expectedGateSessionId: String?): Boolean =
        gateSessionId != null && gateSessionId == expectedGateSessionId && gateFocused

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

        val currentSnapshot = snapshot(state, runtime, nowWallMs, zoneId = zoneId)
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
        val awarded = applyAward(
            state, verificationId, accumulation.completedSets, accumulation.remainder,
            state.dailyCounter.copy(exercisePointSixths = accumulation.remainder),
            if (exerciseId == ExerciseId.PUSH_UP) value else 0L, nowWallMs, zoneId,
        )
        return if (awarded is CommandResult.Applied) awarded.copy(state = awarded.state.copy(
            lastExercise = ExerciseReceipt(
                verificationId, state.dailyCounter.localDayKey, nowWallMs, exerciseId, value,
                pointsBefore = state.dailyCounter.exercisePointSixths,
                awardedSets = accumulation.completedSets,
                legacyEveningResetApplied = state.dailyCounter.legacyEveningResetApplied,
            ),
        )) else awarded
    }

    /** Only the latest saved, positive verifier receipt can authorize a correction. */
    fun correctExercise(
        input: PersistedState, receiptId: String, exercise: ExerciseId, value: Long,
        flagOnly: Boolean, nowWallMs: Long, zoneId: ZoneId,
    ): CommandResult {
        val state = normalize(input, nowWallMs, zoneId)
        val receipt = state.lastExercise
            ?: return rejected(state, "No verified exercise is available to correct")
        if (receipt.id != receiptId || !receipt.canCorrect(state.dailyCounter)) {
            return rejected(state, "This set can no longer be corrected. Record a new exercise.")
        }
        if (flagOnly) return CommandResult.Applied(
            state.copy(stateRevision = nextRevision(state.stateRevision), lastExercise = receipt.copy(flagged = true)),
            RewardCommit(0, state.dailyCounter.exercisePointSixths, receipt.dayKey),
        )
        if (ExercisePolicies.find(exercise)?.credited != true || value !in 0..9_999L) {
            return rejected(state, "Choose a supported exercise and a whole count from 0 to 9,999")
        }
        return applyExerciseCorrection(state, receipt, exercise, value, nowWallMs, zoneId)
    }

    private fun applyExerciseCorrection(state: PersistedState, receipt: ExerciseReceipt,
        exercise: ExerciseId, value: Long, nowWallMs: Long, zoneId: ZoneId): CommandResult {
        val accumulation = accumulateExerciseUnits(receipt.pointsBefore,
            value * exercisePointSixthsPerUnit(exercise), EXERCISE_POINT_SIXTHS_PER_BOOST)
        val delta = accumulation.completedSets - receipt.awardedSets
        val counter = state.dailyCounter
        val earned = counter.earnedBudgetRounds + delta
        val spendable = counter.spendableBudgetRounds + delta
        if (earned !in 0..MAX_SAFE_BUDGET_ROUNDS || spendable !in 0..earned) {
            return rejected(state, "The corrected budget is outside its safe range")
        }
        val pushupDelta = (if (exercise == ExerciseId.PUSH_UP) value else 0L) -
            (if (receipt.exercise == ExerciseId.PUSH_UP) receipt.value else 0L)
        val cycleKey = MonthlyPushupCalculator.cycleStartKey(receipt.recordedAtWallMs, zoneId)
        val cycleTotal = state.monthlyPushupCycles.find { it.cycleStartDate == cycleKey }?.totalPushups ?: 0L
        val error = pushupCorrectionError(counter.verifiedPushups, cycleTotal, pushupDelta)
        if (error != null) return rejected(state, error)
        val cycles = state.monthlyPushupCycles.withCorrectedPushups(cycleKey, pushupDelta)
        val events = state.activityEvents.withCorrectedExerciseEvent(receipt, accumulation.completedSets)
        val history = correctedExerciseHistory(state, receipt, exercise, value, nowWallMs)
        return CommandResult.Applied(state.copy(
            stateRevision = nextRevision(state.stateRevision),
            lastExercise = receipt.copy(exercise = exercise, value = value,
                awardedSets = accumulation.completedSets, corrected = true, flagged = true),
            correctionHistory = history,
            dailyCounter = counter.copy(earnedBudgetRounds = earned, spendableBudgetRounds = spendable,
                exercisePointSixths = accumulation.remainder, verifiedPushups = counter.verifiedPushups + pushupDelta,
                grantedBudgetMinutes = counter.grantedBudgetMinutes?.let { (it + delta * state.configuration.settings.minutesPerCredit).coerceAtLeast(0) }),
            monthlyPushupCycles = cycles.sortedBy { it.cycleStartDate },
            usageReports = state.usageReports.withExerciseSets(receipt.dayKey, earned),
            activityEvents = events.sortedBy { it.startedAtWallMs }.takeLast(MAX_ACTIVITY_EVENTS),
        ), RewardCommit(delta, accumulation.remainder, receipt.dayKey))
    }

    private fun pushupCorrectionError(verified: Long, cycleTotal: Long, delta: Long): String? = when {
        delta > 0 && cycleTotal > Long.MAX_VALUE - delta -> "Push-up total is at its safe limit"
        verified + delta < 0 || cycleTotal + delta < 0 -> "Exercise totals changed. Please record a new set."
        else -> null
    }

    private fun List<MonthlyPushupCycle>.withCorrectedPushups(key: String, delta: Long): List<MonthlyPushupCycle> {
        val cycles = toMutableList()
        val index = cycles.indexOfFirst { it.cycleStartDate == key }
        if (index >= 0) cycles[index] = cycles[index].copy(totalPushups = cycles[index].totalPushups + delta)
        else if (delta > 0) cycles.add(MonthlyPushupCycle(key, delta))
        return cycles
    }

    private fun List<ActivityEvent>.withCorrectedExerciseEvent(receipt: ExerciseReceipt, sets: Long): List<ActivityEvent> =
        filterNot { it.id == receipt.id } + if (sets > 0) listOf(ActivityEvent(
            receipt.id, receipt.dayKey, ActivityEventType.EXERCISE_SET,
            receipt.recordedAtWallMs, receipt.recordedAtWallMs, sets * EXERCISE_SET_REWARD_MINUTES * MINUTE_MS,
        )) else emptyList()

    private fun correctedExerciseHistory(state: PersistedState, receipt: ExerciseReceipt,
        exercise: ExerciseId, value: Long, nowWallMs: Long): List<CorrectionEvent> =
        if (exercise != receipt.exercise || value != receipt.value) state.correctionHistory + CorrectionEvent(
            idGenerator.nextId(), receipt.id, nowWallMs, receipt.originalExercise.wireId,
            receipt.originalValue, exercise.wireId, value,
        ) else state.correctionHistory

    fun recordSprintRecording(
        input: PersistedState,
        recordingId: String,
        qualifyingMeters: Long,
        manualCompletionAward: Boolean = false,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult {
        val state = normalize(input, nowWallMs, zoneId)
        if (qualifyingMeters < 0) return rejected(state, "Sprint distance cannot be negative")
        val accumulation = try {
            if (manualCompletionAward) ExerciseAccumulation(1L, state.dailyCounter.sprintMetersRemainder)
            else accumulateExerciseUnits(
                state.dailyCounter.sprintMetersRemainder, qualifyingMeters, SPRINT_SET_QUALIFYING_METERS,
            )
        } catch (_: ArithmeticException) {
            return rejected(state, "Sprint distance measurement is too large")
        }
        return applyAward(
            state, recordingId, accumulation.completedSets, accumulation.remainder,
            state.dailyCounter.copy(sprintMetersRemainder = accumulation.remainder),
            0L, nowWallMs, zoneId,
        )
    }

    private fun applyAward(
        state: PersistedState,
        receiptId: String,
        sets: Long,
        remainder: Long,
        counter: DailyCounter,
        pushups: Long,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): CommandResult {
        awardValidationError(receiptId, counter, sets)?.let { return rejected(state, it) }
        val totalSets = counter.earnedBudgetRounds + sets
        val verifiedPushups: Long
        val cycles: List<MonthlyPushupCycle>
        try {
            verifiedPushups = Math.addExact(counter.verifiedPushups, pushups)
            cycles = if (pushups > 0) MonthlyPushupCalculator.addPushups(
                state.monthlyPushupCycles, pushups, nowWallMs, zoneId,
            ) else state.monthlyPushupCycles
        } catch (_: ArithmeticException) {
            return rejected(state, "Push-up total is at its safe limit")
        }
        return CommandResult.Applied(
            state.copy(
                stateRevision = nextRevision(state.stateRevision),
                dailyCounter = counter.copy(
                    earnedBudgetRounds = totalSets,
                    spendableBudgetRounds = counter.spendableBudgetRounds + sets,
                    verifiedPushups = verifiedPushups,
                    grantedBudgetMinutes = counter.grantedBudgetMinutes?.let { Math.addExact(it, sets * state.configuration.settings.minutesPerCredit) },
                    creditedExerciseVerificationIds = counter.creditedExerciseVerificationIds + receiptId,
                ),
                usageReports = state.usageReports.withExerciseSets(counter.localDayKey, totalSets),
                monthlyPushupCycles = cycles,
                activityEvents = if (sets > 0) {
                    (state.activityEvents + ActivityEvent(
                        id = receiptId,
                        localDayKey = counter.localDayKey,
                        type = ActivityEventType.EXERCISE_SET,
                        startedAtWallMs = nowWallMs,
                        endedAtWallMs = nowWallMs,
                        valueMs = sets * EXERCISE_SET_REWARD_MINUTES * MINUTE_MS,
                    )).takeLast(MAX_ACTIVITY_EVENTS)
                } else state.activityEvents,
            ),
            RewardCommit(sets, remainder, counter.localDayKey),
        )
    }

    private fun awardValidationError(receiptId: String, counter: DailyCounter, sets: Long): String? {
        if (receiptId.isBlank() || receiptId.length > 128) return "Invalid reward receipt ID"
        if (receiptId in counter.creditedExerciseVerificationIds) return "Reward was already credited"
        if (counter.creditedExerciseVerificationIds.size >= MAX_DAILY_CREDITED_EXERCISE_VERIFICATIONS) {
            return "Daily exercise verification history is at its safe limit"
        }
        if (sets > MAX_SAFE_BUDGET_ROUNDS - counter.earnedBudgetRounds ||
            sets > MAX_SAFE_BUDGET_ROUNDS - counter.spendableBudgetRounds) {
            return "Daily earned budget is at its safe limit"
        }
        return null
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
        if (!initialMode.traits.recordsUsage) return UsageConsumptionResult(state, 0L, 0L)

        val originalExhausted = budgetSnapshots(state).any { it.exhausted }
        val consumed = consumeUsageSegments(state, runtime, intervalStartWallMs, intervalEndWallMs, zoneId)
        state = consumed.state
        val nowExhausted = budgetSnapshots(state).any { it.exhausted }
        if (!originalExhausted && nowExhausted) state = state.copy(stateRevision = nextRevision(state.stateRevision))
        return consumed.copy(state = state)
    }

    private fun consumeUsageSegments(input: PersistedState, runtime: RuntimeSignals,
        intervalStartWallMs: Long, intervalEndWallMs: Long, zoneId: ZoneId): UsageConsumptionResult {
        var state = input
        var cursor = intervalStartWallMs
        var totalRecordedMs = 0L
        var totalChargedMs = 0L
        while (cursor < intervalEndWallMs) {
            state = normalize(state, cursor, zoneId)
            val mode = underlyingMode(state, runtime, cursor)
            if (!mode.traits.recordsUsage) break

            val segmentEnd = nextUsageBoundary(state, cursor, intervalEndWallMs, zoneId)
            val segmentDuration = segmentEnd - cursor
            if (segmentDuration <= 0) break

            val accessible = accessibleDuration(state, mode, segmentDuration)
            val budgetCharged = if (mode.traits.chargesBudget) accessible else 0L

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
        val finalState = state.copy(runtimeMetadata = state.runtimeMetadata.copy(lastCommittedWallMs = cursor))
        return UsageConsumptionResult(finalState, totalRecordedMs, totalChargedMs)
    }

    private fun nextUsageBoundary(state: PersistedState, cursor: Long, intervalEndWallMs: Long, zoneId: ZoneId): Long {
            val dailyReset = PeriodCalculator.nextDailyReset(cursor, zoneId, state.configuration.settings)
            val resetBoundary = dailyReset
            val unlockBoundary = state.activeUnlock?.expiresAtWallMs?.takeIf { it > cursor }
            val nextBoundary = listOfNotNull(resetBoundary, unlockBoundary).minOrNull() ?: intervalEndWallMs
            return minOf(intervalEndWallMs, nextBoundary)
    }

    private fun accessibleDuration(state: PersistedState, mode: GateMode, duration: Long): Long {
        if (!mode.traits.chargesBudget) return duration
        val remaining = budgetSnapshots(state).filter { it.enabled }
            .minOfOrNull { it.remainingMs ?: Long.MAX_VALUE } ?: duration
        return minOf(duration, remaining).coerceAtLeast(0)
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
            input.configuration.settings,
        )) {
            val key = PeriodCalculator.keysAt(interval.startMs, zoneId, input.configuration.settings).daily
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
        val cutoff = LocalDate.parse(PeriodCalculator.keysAt(intervalEndWallMs - 1, zoneId, input.configuration.settings).daily)
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
        else -> recoveryHealth(runtime)
    }

    private fun recoveryHealth(runtime: RuntimeSignals): ProtectionHealth =
        if (runtime.usageRecoveryOperational && runtime.usageAccessGranted) ProtectionHealth.READY
        else ProtectionHealth.READY_LIMITED_RECOVERY

    private fun currentWeekReports(
        state: PersistedState,
        nowWallMs: Long,
        zoneId: ZoneId,
    ): List<DailyUsageReport> {
        val today = LocalDate.parse(PeriodCalculator.keysAt(nowWallMs, zoneId, state.configuration.settings).daily)
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
    }
}
