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
        val configSettings = input.configuration.settings
        val keys = PeriodCalculator.keysAt(nowWallMs, zoneId, configSettings)
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
            zoneId,
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
            state = state.copy(
                dailyCounter = DailyCounter(localDayKey = keys.daily,
                    grantedBudgetMinutes = configSettings.dailyBudgetMinutes.takeIf { state.dailyCounter.grantedBudgetMinutes != null || it > 0 }),
                taskCreditProgress = CreditFraction(), taskProgressBase = CreditFraction(), taskBudgetKey = "",
                lastRewardResetWallMs = if (state.dailyCounter.localDayKey.isNotBlank())
                    maxOf(state.lastRewardResetWallMs, PeriodCalculator.dailyResetStart(nowWallMs, zoneId, configSettings))
                    else state.lastRewardResetWallMs,
                lastDailyRewardResetWallMs = maxOf(state.lastDailyRewardResetWallMs,
                    PeriodCalculator.dailyResetStart(nowWallMs, zoneId, configSettings)),
            )
            revisionRelevant = true
        }
        if (state.lastDailyRewardResetWallMs == 0L) {
            state = state.copy(lastDailyRewardResetWallMs = PeriodCalculator.dailyResetStart(nowWallMs, zoneId, configSettings))
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

    fun underlyingMode(state: PersistedState, runtime: RuntimeSignals, nowWallMs: Long): GateMode {
        if (runtime.storageError) return GateMode.STORAGE_ERROR
        if (runtime.activeTarget == null || !runtime.screenInteractive || !runtime.keyguardUnlocked) {
            return GateMode.BROWSER_BACKGROUND
        }
        if (!runtime.storageReady || !runtime.accessibilityEnabled || !runtime.serviceConnected) {
            return GateMode.SERVICE_UNAVAILABLE
        }
        if (runtime.activeTarget == ProtectedTarget.YOUTUBE) {
            return if (state.dailyCounter.earnedBudgetRounds >= state.configuration.settings.youtubeCredits) {
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
        val currentMonthlyCycleKey = MonthlyPushupCalculator.cycleStartKey(nowWallMs, zoneId)
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
        val grantDuration = state.configuration.settings.emergencyMinutes * MINUTE_MS
        val skipModeEligible = when (mode) {
            GateMode.PAUSED, GateMode.WAITING -> true
            GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> true
            else -> false
        }
        val activeUnlockIsCurrent = state.activeUnlock?.let {
            it.expiresAtWallMs > nowWallMs &&
                (it.source == UnlockSource.EMERGENCY || budgets.none { budget -> budget.exhausted })
        } == true
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
        val cycles = state.monthlyPushupCycles.toMutableList()
        val index = cycles.indexOfFirst { it.cycleStartDate == cycleKey }
        val cycleTotal = (cycles.getOrNull(index)?.totalPushups ?: 0L)
        if (pushupDelta > 0 && cycleTotal > Long.MAX_VALUE - pushupDelta) {
            return rejected(state, "Push-up total is at its safe limit")
        }
        if (counter.verifiedPushups + pushupDelta < 0 || cycleTotal + pushupDelta < 0) {
            return rejected(state, "Exercise totals changed. Please record a new set.")
        }
        if (index >= 0) cycles[index] = cycles[index].copy(totalPushups = cycleTotal + pushupDelta)
        else if (pushupDelta > 0) cycles.add(MonthlyPushupCycle(cycleKey, pushupDelta))
        val events = state.activityEvents.filterNot { it.id == receipt.id } +
            if (accumulation.completedSets > 0) listOf(ActivityEvent(
                receipt.id, receipt.dayKey, ActivityEventType.EXERCISE_SET,
                receipt.recordedAtWallMs, receipt.recordedAtWallMs,
                accumulation.completedSets * EXERCISE_SET_REWARD_MINUTES * MINUTE_MS,
            )) else emptyList()
        val history = if (exercise != receipt.exercise || value != receipt.value) {
            state.correctionHistory + CorrectionEvent(
                id = idGenerator.nextId(),
                receiptId = receipt.id,
                correctedAtWallMs = nowWallMs,
                originalType = receipt.originalExercise.wireId,
                originalValue = receipt.originalValue,
                correctedType = exercise.wireId,
                correctedValue = value,
            )
        } else state.correctionHistory
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
        if (receiptId.isBlank() || receiptId.length > 128) return rejected(state, "Invalid reward receipt ID")
        if (receiptId in counter.creditedExerciseVerificationIds) return rejected(state, "Reward was already credited")
        if (counter.creditedExerciseVerificationIds.size >= MAX_DAILY_CREDITED_EXERCISE_VERIFICATIONS) {
            return rejected(state, "Daily exercise verification history is at its safe limit")
        }
        if (sets > MAX_SAFE_BUDGET_ROUNDS - counter.earnedBudgetRounds ||
            sets > MAX_SAFE_BUDGET_ROUNDS - counter.spendableBudgetRounds) {
            return rejected(state, "Daily earned budget is at its safe limit")
        }
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

        var cursor = intervalStartWallMs
        var totalRecordedMs = 0L
        var totalChargedMs = 0L
        val originalExhausted = budgetSnapshots(state).any { it.exhausted }
        while (cursor < intervalEndWallMs) {
            state = normalize(state, cursor, zoneId)
            val mode = underlyingMode(state, runtime, cursor)
            if (!mode.traits.recordsUsage) break

            val dailyReset = PeriodCalculator.nextDailyReset(cursor, zoneId, state.configuration.settings)
            val resetBoundary = dailyReset
            val unlockBoundary = state.activeUnlock?.expiresAtWallMs?.takeIf { it > cursor }
            val nextBoundary = listOfNotNull(resetBoundary, unlockBoundary).minOrNull() ?: intervalEndWallMs
            val segmentEnd = minOf(intervalEndWallMs, nextBoundary)
            val segmentDuration = segmentEnd - cursor
            if (segmentDuration <= 0) break

            val budgets = budgetSnapshots(state)
            val isEmergency = !mode.traits.chargesBudget

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
        runtime.usageRecoveryOperational && runtime.usageAccessGranted -> ProtectionHealth.READY
        else -> ProtectionHealth.READY_LIMITED_RECOVERY
    }

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
