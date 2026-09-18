package com.michalkrsik.internetrestrictor.domain

import java.time.ZoneId
import java.util.UUID
import java.math.BigDecimal

/** Configuration writes are atomic with the native revision used for backend acknowledgement. */
fun RestrictionEngine.applyConfiguration(
    input: PersistedState, configuration: AppConfiguration, nowWallMs: Long, zoneId: ZoneId,
): CommandResult {
    val state = normalize(input, nowWallMs, zoneId)
    configuration.validationError()?.let { return CommandResult.Rejected(state, it) }
    if (state.hasAppliedServerConfiguration && configuration.revision < state.configuration.revision) return CommandResult.Rejected(state, "Configuration is older than the applied revision")
    if (state.hasAppliedServerConfiguration && configuration.revision == state.configuration.revision && configuration != state.configuration) {
        return CommandResult.Rejected(state, "Different configuration uses the same revision")
    }
    if (state.hasAppliedServerConfiguration && configuration == state.configuration && state.dailyCounter.grantedBudgetMinutes != null) return CommandResult.Applied(state)
    val settings = configuration.settings
    val resetsChanged = settings.dayReset != state.configuration.settings.dayReset
    val dayKey = PeriodCalculator.keysAt(nowWallMs, zoneId, settings).daily
    val counter = state.dailyCounter.copy(
        // Freeze the value of already-earned credits before changing future reward rates.
        grantedBudgetMinutes = state.dailyCounter.grantedBudgetMinutes ?: state.dailyCounter.spendableBudgetRounds * EXERCISE_SET_REWARD_MINUTES,
        localDayKey = if (resetsChanged) dayKey else state.dailyCounter.localDayKey,
    )
    return CommandResult.Applied(state.copy(
        stateRevision = Math.addExact(state.stateRevision, 1),
        configuration = configuration,
        hasAppliedServerConfiguration = true,
        settings = state.settings.copy(sessionMinutes = settings.sessionMinutes),
        dailyCounter = counter,
        dailyUsage = if (resetsChanged) state.dailyUsage.copy(key = dayKey) else state.dailyUsage,
        // A changed clock schedule starts from current balances. Moving the clock back
        // never reloads previously expired balances or old partial sets.
        // Keep this ledger's opaque ID when clocks are edited. Reusing a date's
        // key would allow an undo to mix in already-expired receipts.
        // Existing running visits keep their agreed duration; an unfinished wait is
        // restarted under the new rules on the next selection.
        pendingWait = if (settings.waitSeconds != state.configuration.settings.waitSeconds || settings.sessionMinutes != state.configuration.settings.sessionMinutes) null else state.pendingWait,
    ))
}

fun RestrictionEngine.applyTaskReceipt(
    input: PersistedState, receipt: TaskMeasurementReceipt, nowWallMs: Long, zoneId: ZoneId,
): CommandResult {
    var state = normalize(input, nowWallMs, zoneId)
    val invalid = receipt.validationError(nowWallMs)
    if (invalid != null) return CommandResult.Rejected(state, invalid)
    val existing = state.taskReceipts.find { it.measurement.id == receipt.id || it.measurement.attemptId == receipt.attemptId }
    if (existing != null) {
        if (receipt.originalMeasurement() != existing.measurement.originalMeasurement()) {
            return CommandResult.Rejected(state, "This receipt ID belongs to a different verified recording")
        }
        if (receipt.manualCompletion && receipt.fallbackEligible && !existing.measurement.manualCompletion) {
            if (!existing.canModifyReward(state.taskBudgetKey, nowWallMs)) return CommandResult.Rejected(state, "This recording's reward period expired")
            if (receipt.task != existing.measurement.task || receipt.id != existing.measurement.id || receipt.task.verifier != "gps") {
                return CommandResult.Rejected(state, "Fallback receipt does not match the original recording")
            }
            val changed = state.taskReceipts.map { if (it == existing) it.copy(
                measurement = it.measurement.copy(manualCompletion = true), value = it.measurement.task.unitsPerCredit,
                valueText = it.measurement.task.unitsPerCredit.toString(),
            ) else it }
            return recomputeTaskRewards(state, changed, nowWallMs, zoneId)
        }
        receipt.correctedUnitsText?.let { corrected ->
            if (BigDecimal(corrected).compareTo(BigDecimal(existing.valueText)) != 0 && !receipt.manualCompletion)
                return correctTaskReceiptValue(state, existing.measurement.id, corrected, nowWallMs, zoneId)
        }
        return CommandResult.Applied(state) // A retry is a successful no-op, including after a reset.
    }
    if (state.taskReceipts.size >= 10_000) return CommandResult.Rejected(state, "Task history is full")
    val pinnedSettings = state.configuration.settings.copy(dayReset = receipt.dayReset)
    val pinnedZone = ZoneId.of(receipt.timeZone)
    val eligible = receipt.rewardEligible && receipt.finishedAtWallMs >= state.lastDailyRewardResetWallMs &&
        PeriodCalculator.keysAt(receipt.finishedAtWallMs, pinnedZone, pinnedSettings).daily == PeriodCalculator.keysAt(nowWallMs, pinnedZone, pinnedSettings).daily
    val minutesEligible = eligible && receipt.minutesRewardEligible && receipt.finishedAtWallMs >= state.lastRewardResetWallMs &&
        PeriodCalculator.budgetKey(receipt.finishedAtWallMs, pinnedZone, pinnedSettings) == PeriodCalculator.budgetKey(nowWallMs, pinnedZone, pinnedSettings)
    val dayKey = PeriodCalculator.keysAt(receipt.finishedAtWallMs, pinnedZone, pinnedSettings).daily
    val currentBudgetKey = state.taskBudgetKey.ifBlank { UUID.randomUUID().toString() }
    val effectiveValue = if (receipt.manualCompletion && receipt.fallbackEligible && receipt.task.verifier == "gps") receipt.task.unitsPerCredit
        else receipt.correctedUnits?.takeIf { receipt.correctionEligible && receipt.value >= receipt.task.correctionMinimum } ?: receipt.value
    val saved = SavedTaskReceipt(receipt, value = effectiveValue, dayKey = dayKey,
        budgetKey = if (eligible) currentBudgetKey else "expired:${receipt.id}", rewardEligible = eligible,
        corrected = receipt.correctedUnitsText != null && !receipt.manualCompletion,
        valueText = if (receipt.manualCompletion) receipt.task.unitsPerCredit.toString() else
            receipt.correctedUnitsText?.takeIf { receipt.correctionEligible && receipt.value >= receipt.task.correctionMinimum } ?: receipt.measuredUnitsText,
        minutesRewardEligible = minutesEligible)
    if (!eligible) return CommandResult.Applied(state.copy(
        stateRevision = Math.addExact(state.stateRevision, 1), taskReceipts = state.taskReceipts + saved,
    ), RewardCommit(0, state.dailyCounter.exercisePointSixths, dayKey))
    if (state.taskBudgetKey != currentBudgetKey) {
        val base = CreditFraction(state.dailyCounter.exercisePointSixths.toString(), EXERCISE_POINT_SIXTHS_PER_BOOST.toString())
        state = state.copy(taskBudgetKey = currentBudgetKey, taskProgressBase = base, taskCreditProgress = base)
    }
    return recomputeTaskRewards(state, state.taskReceipts + saved, nowWallMs, zoneId)
}

fun RestrictionEngine.correctTaskReceipt(
    input: PersistedState, receiptId: String, value: Long, nowWallMs: Long, zoneId: ZoneId, flagOnly: Boolean = false,
): CommandResult = correctTaskReceiptValue(input, receiptId, value.toString(), nowWallMs, zoneId, flagOnly)

private fun RestrictionEngine.correctTaskReceiptValue(
    input: PersistedState, receiptId: String, valueText: String, nowWallMs: Long, zoneId: ZoneId, flagOnly: Boolean = false,
): CommandResult {
    val state = normalize(input, nowWallMs, zoneId)
    val saved = state.taskReceipts.find { it.measurement.id == receiptId }
        ?: return CommandResult.Rejected(state, "The original verified task could not be found")
    if (flagOnly) return CommandResult.Applied(state.copy(
        stateRevision = Math.addExact(state.stateRevision, 1),
        taskReceipts = state.taskReceipts.map { if (it == saved) it.copy(flagged = true) else it },
    ))
    if (saved.measurement.manualCompletion) return CommandResult.Rejected(state, "A manually completed GPS task cannot be corrected again")
    if (!saved.measurement.correctionEligible || saved.measurement.value < saved.measurement.task.correctionMinimum) {
        return CommandResult.Rejected(state, "The original recording did not show the minimum evidence needed for a correction")
    }
    if (!validTaskMeasurement(valueText, 100_000)) return CommandResult.Rejected(state, "Correction is outside the supported range")
    val value = BigDecimal(valueText).toLong()
    if (saved.measurement.task.metric == "repetitions" && BigDecimal(valueText).stripTrailingZeros().scale() > 0) return CommandResult.Rejected(state, "Repetitions must be a whole count")
    if (saved.measurement.task.metric == "seconds" && BigDecimal(valueText).toDouble() >
        (saved.measurement.maximumCorrectableUnits ?: minOf(30.5,
            (saved.measurement.finishedAtWallMs - saved.measurement.startedAtWallMs) / 1000.0 + 0.5))) return CommandResult.Rejected(state, "Corrected time cannot exceed the recording")
    if (BigDecimal(valueText).compareTo(BigDecimal(saved.valueText)) == 0) return CommandResult.Applied(state)
    if (!saved.canModifyReward(state.taskBudgetKey, nowWallMs)) {
        return CommandResult.Rejected(state, "This task's reward period has expired; a correction cannot restore old rewards")
    }
    val edited = state.taskReceipts.map { if (it == saved) it.copy(value = value, valueText = valueText, corrected = true, flagged = true) else it }
    val result = recomputeTaskRewards(state, edited, nowWallMs, zoneId)
    if (result !is CommandResult.Applied) return result
    return result.copy(state = result.state.copy(correctionHistory = state.correctionHistory + CorrectionEvent(
        UUID.randomUUID().toString(), receiptId, nowWallMs, saved.measurement.taskId, saved.measurement.value,
        saved.measurement.taskId, value,
    )))
}

/** Historical reward eligibility is immutable; modification additionally needs the
 * active native ledger and the recording's pinned server reset window. */
internal fun SavedTaskReceipt.canModifyReward(currentLedgerId: String, nowWallMs: Long): Boolean {
    if (!rewardEligible || !minutesRewardEligible || currentLedgerId.isBlank() || budgetKey != currentLedgerId) return false
    val pinned = ConfigurationSettings(dayReset = measurement.dayReset)
    val zone = ZoneId.of(measurement.timeZone)
    return PeriodCalculator.budgetKey(measurement.finishedAtWallMs, zone, pinned) == PeriodCalculator.budgetKey(nowWallMs, zone, pinned)
}

private fun recomputeTaskRewards(state: PersistedState, receipts: List<SavedTaskReceipt>, nowWallMs: Long, zoneId: ZoneId): CommandResult {
    return try {
        var fraction = state.taskProgressBase
        // Ledger order is application order. A delayed valid recording must not
        // retroactively move another recording's reward to a different rate.
        val current = receipts.filter { it.rewardEligible && it.budgetKey == state.taskBudgetKey }
        val replacements = current.associate { receipt ->
            val total = fraction.plus(receipt.valueText, receipt.measurement.task.unitsPerCredit)
            val credits = total.whole
            fraction = total.remainder()
            receipt.measurement.id to receipt.copy(awardedCredits = credits,
                awardedMinutes = if (receipt.minutesRewardEligible) Math.multiplyExact(credits, receipt.measurement.minutesPerCredit) else 0)
        }
        val before = state.taskReceipts.filter { it.rewardEligible && it.budgetKey == state.taskBudgetKey }
        val deltaCredits = replacements.values.sumOf { it.awardedCredits } - before.sumOf { it.awardedCredits }
        val deltaSpendableCredits = replacements.values.filter { it.minutesRewardEligible }.sumOf { it.awardedCredits } -
            before.filter { it.minutesRewardEligible }.sumOf { it.awardedCredits }
        val deltaMinutes = replacements.values.sumOf { it.awardedMinutes } - before.sumOf { it.awardedMinutes }
        val earned = Math.addExact(state.dailyCounter.earnedBudgetRounds, deltaCredits)
        val spendable = Math.addExact(state.dailyCounter.spendableBudgetRounds, deltaSpendableCredits)
        val minutes = Math.addExact(state.dailyCounter.grantedBudgetMinutes ?: state.dailyCounter.spendableBudgetRounds * EXERCISE_SET_REWARD_MINUTES, deltaMinutes)
        require(earned in 0..MAX_SAFE_BUDGET_ROUNDS && spendable in 0..earned && minutes in 0..MAX_SAFE_MINUTES && fraction.valid())
        val changedIds = replacements.keys
        val eventReceipts = replacements.values.filter { it.awardedCredits > 0 }.map { saved ->
            ActivityEvent(saved.measurement.id, saved.dayKey, ActivityEventType.EXERCISE_SET,
                saved.measurement.startedAtWallMs, saved.measurement.finishedAtWallMs, saved.awardedMinutes * MINUTE_MS)
        }
        val reports = state.usageReports.associateBy { it.localDayKey }.toMutableMap()
        val report = reports[state.dailyCounter.localDayKey] ?: DailyUsageReport(state.dailyCounter.localDayKey)
        reports[report.localDayKey] = report.copy(exerciseSets = earned)
        val oldPushups = before.filter { it.measurement.taskId == "pushups" }.sumOf { it.value }
        val newPushups = current.filter { it.measurement.taskId == "pushups" }.sumOf { it.value }
        val pushupDelta = newPushups - oldPushups
        var cycles = state.monthlyPushupCycles
        if (pushupDelta != 0L) {
            val cycleKey = MonthlyPushupCalculator.cycleStartKey(nowWallMs, zoneId)
            val previous = cycles.find { it.cycleStartDate == cycleKey }?.totalPushups ?: 0
            require(previous + pushupDelta >= 0)
            cycles = (cycles.filterNot { it.cycleStartDate == cycleKey } + MonthlyPushupCycle(cycleKey, Math.addExact(previous, pushupDelta))).sortedBy { it.cycleStartDate }
        }
        CommandResult.Applied(state.copy(
            stateRevision = Math.addExact(state.stateRevision, 1),
            taskReceipts = receipts.map { replacements[it.measurement.id] ?: it },
            taskCreditProgress = fraction,
            dailyCounter = state.dailyCounter.copy(earnedBudgetRounds = earned, spendableBudgetRounds = spendable,
                grantedBudgetMinutes = minutes, exercisePointSixths = (fraction.approximate * EXERCISE_POINT_SIXTHS_PER_BOOST).toLong().coerceIn(0, EXERCISE_POINT_SIXTHS_PER_BOOST - 1),
                verifiedPushups = Math.addExact(state.dailyCounter.verifiedPushups, pushupDelta)),
            usageReports = reports.values.sortedBy { it.localDayKey }.takeLast(RestrictionEngine.MAX_REPORT_DAYS),
            activityEvents = (state.activityEvents.filterNot { it.id in changedIds } + eventReceipts).sortedBy { it.startedAtWallMs }.takeLast(RestrictionEngine.MAX_ACTIVITY_EVENTS),
            monthlyPushupCycles = cycles,
        ), RewardCommit(deltaCredits, (fraction.approximate * EXERCISE_POINT_SIXTHS_PER_BOOST).toLong(), state.dailyCounter.localDayKey))
    } catch (_: ArithmeticException) { CommandResult.Rejected(state, "Task reward exceeds the supported safe range")
    } catch (_: IllegalArgumentException) { CommandResult.Rejected(state, "Task reward would produce invalid balances") }
}

fun TaskMeasurementReceipt.validationError(nowWallMs: Long = Long.MAX_VALUE): String? = when {
    id.isBlank() || id.length > 128 || attemptId.isBlank() || attemptId.length > 128 -> "Invalid verification receipt ID"
    taskId != task.id || task.validationError() != null -> "Invalid pinned task definition"
    configRevision < 0 || !verified -> "Receipt has not been verified"
    value !in 0..1_000_000 || minutesPerCredit !in 1..120 -> "Receipt measurement or reward is outside the supported range"
    !validTaskMeasurement(measuredUnitsText) || BigDecimal(measuredUnitsText).toLong() != value -> "Invalid precise receipt measurement"
    task.metric == "repetitions" && BigDecimal(measuredUnitsText).stripTrailingZeros().scale() > 0 -> "Repetitions must be a whole count"
    correctedUnitsText?.let { !validTaskMeasurement(it, 100_000) || BigDecimal(it).toLong() != correctedUnits } == true -> "Invalid precise corrected measurement"
    correctedUnits?.let { it !in 0..1_000_000 } == true -> "Corrected receipt measurement is outside the supported range"
    maximumCorrectableUnits?.let { !it.isFinite() || it !in 0.0..30.5 || task.verifier != "video" || task.metric != "seconds" } == true -> "Invalid recording correction maximum"
    manualCompletion && (!fallbackEligible || task.verifier != "gps") -> "Manual completion needs an eligible GPS receipt"
    startedAtWallMs < 0 || finishedAtWallMs < startedAtWallMs || finishedAtWallMs > nowWallMs -> "Invalid recording timestamps"
    !validResetTime(dayReset) || runCatching { ZoneId.of(timeZone) }.isFailure -> "Invalid receipt reset settings"
    else -> null
}

internal fun validTaskMeasurement(text: String, maximum: Long = 1_000_000): Boolean = runCatching {
    text.length <= 256 && BigDecimal(text).let { it.scale() <= 128 && it.signum() >= 0 && it <= maximum.toBigDecimal() }
}.getOrDefault(false)

private fun TaskMeasurementReceipt.originalMeasurement() = copy(
    correctedUnits = null, correctedUnitsText = null, manualCompletion = false,
)
