package com.michalkrsik.internetrestrictor.data

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import com.michalkrsik.internetrestrictor.domain.ActivityEvent
import com.michalkrsik.internetrestrictor.domain.ActivityEventType
import com.michalkrsik.internetrestrictor.domain.BudgetPeriod
import com.michalkrsik.internetrestrictor.domain.DailyCounter
import com.michalkrsik.internetrestrictor.domain.DailyUsageReport
import com.michalkrsik.internetrestrictor.domain.DEFAULT_SESSION_MINUTES
import com.michalkrsik.internetrestrictor.domain.EMERGENCY_SKIP_DAILY_LIMIT
import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.MACHINE_REPS_PER_SET
import com.michalkrsik.internetrestrictor.domain.MAX_DAILY_CREDITED_EXERCISE_VERIFICATIONS
import com.michalkrsik.internetrestrictor.domain.MAX_SESSION_MINUTES
import com.michalkrsik.internetrestrictor.domain.MAX_SAFE_BUDGET_ROUNDS
import com.michalkrsik.internetrestrictor.domain.MIN_SESSION_MINUTES
import com.michalkrsik.internetrestrictor.domain.MonthlyPushupCycle
import com.michalkrsik.internetrestrictor.domain.PendingWait
import com.michalkrsik.internetrestrictor.domain.OffUsageReminderState
import com.michalkrsik.internetrestrictor.domain.PersistedState
import com.michalkrsik.internetrestrictor.domain.PLANK_SECONDS_PER_SET
import com.michalkrsik.internetrestrictor.domain.PUSHUPS_PER_SET
import com.michalkrsik.internetrestrictor.domain.RESTRICTOR_SCHEMA_VERSION
import com.michalkrsik.internetrestrictor.domain.RestrictionEngine
import com.michalkrsik.internetrestrictor.domain.RestrictorSettings
import com.michalkrsik.internetrestrictor.domain.RuntimeMetadata
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.domain.UnlockSession
import com.michalkrsik.internetrestrictor.domain.UnlockSource
import com.michalkrsik.internetrestrictor.domain.UsagePeriodState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

object AppStateSerializer : Serializer<PersistedState> {
    private const val MAGIC = 0x49525354 // IRST

    override val defaultValue: PersistedState = PersistedState()

    override suspend fun readFrom(input: InputStream): PersistedState {
        try {
            val source = DataInputStream(input)
            if (source.readInt() != MAGIC) throw CorruptionException("Unrecognized state file")
            val schema = source.readInt()
            if (schema !in 1..RESTRICTOR_SCHEMA_VERSION) {
                throw CorruptionException("Unsupported state schema: $schema")
            }
            val revision = source.readLong()
            source.readBoolean() // Retired enforcement toggle. Protection is always on.
            if (schema >= 2 && source.readBoolean()) source.readLong() // Retired pause end.
            source.readBoolean() // Retired wait toggle. The focused wait is always required.
            if (schema <= 2) {
                // Legacy builds stored configurable daily, weekly, and monthly limits.
                repeat(3) {
                    source.readBoolean()
                    source.readLong()
                }
            }
            source.readBoolean() // Retired emergency-skip toggle. Skips are always enabled.
            source.readLong() // Retired configurable emergency-skip limit.
            val theme = source.readEnum<ThemePreference>()
            val onboardingCompleted = source.readBoolean()
            val sessionMinutes = if (schema >= 4) {
                val count = source.readInt()
                if (count != DEFAULT_SESSION_MINUTES.size) {
                    throw CorruptionException("Invalid session preset count")
                }
                List(count) { source.readLong() }
            } else {
                DEFAULT_SESSION_MINUTES
            }
            val settings = RestrictorSettings(
                enforcementEnabled = true,
                pauseUntilWallMs = null,
                delayEnabled = true,
                emergencySkipsEnabled = true,
                emergencySkipDailyLimit = EMERGENCY_SKIP_DAILY_LIMIT,
                theme = theme,
                onboardingCompleted = onboardingCompleted,
                sessionMinutes = sessionMinutes,
            )
            val daily = source.readUsage(BudgetPeriod.DAILY)
            if (schema <= 2) {
                // Consume the retired weekly and monthly usage records.
                source.readUTF()
                source.readLong()
                source.readUTF()
                source.readLong()
            }
            val unlock = if (source.readBoolean()) {
                val id = source.readUTF()
                val sourceOrdinal = source.readInt()
                val grantedAtWallMs = source.readLong()
                val expiresAtWallMs = source.readLong()
                val unlockSource = UnlockSource.entries.getOrNull(sourceOrdinal)
                if (schema <= 2 && sourceOrdinal == 2) {
                    // A legacy push-up unlock is deliberately not migrated into earned budget.
                    null
                } else {
                    UnlockSession(
                        id = id,
                        source = unlockSource
                            ?: throw CorruptionException("Invalid unlock source"),
                        grantedAtWallMs = grantedAtWallMs,
                        expiresAtWallMs = expiresAtWallMs,
                    )
                }
            } else null
            val pending = if (source.readBoolean()) {
                val storedPending = PendingWait(
                    id = source.readUTF(),
                    selectedDurationMs = source.readLong(),
                    requiredFocusMs = source.readLong(),
                    accumulatedFocusMs = source.readLong(),
                    createdAtWallMs = source.readLong(),
                    updatedAtWallMs = source.readLong(),
                )
                if (schema <= 2) {
                    // Legacy waits used the retired escalating formula. Restart at the fixed
                    // 12-second gate instead of carrying an incompatible partial wait forward.
                    null
                } else {
                    storedPending
                }
            } else null
            val localDayKey = source.readUTF()
            val earnedBudgetRounds = if (schema >= 3) {
                source.readLong()
            } else {
                source.readLong() // Retired completed-normal-unlock count.
                0L
            }
            val emergencySkipsUsed = source.readLong()
            val pushupRepetitionRemainder = if (schema >= 7) source.readLong() else 0L
            val spendableBudgetRounds = if (schema >= 8) source.readLong() else earnedBudgetRounds
            val eveningResetApplied = if (schema >= 8) source.readBoolean() else false
            val verifiedPushups: Long
            val plankSecondRemainder: Long
            val machineRepetitionRemainder: Long
            val creditedExerciseVerificationIds: List<String>
            if (schema >= 10) {
                verifiedPushups = source.readLong()
                plankSecondRemainder = source.readLong()
                machineRepetitionRemainder = source.readLong()
                val creditedVerificationCount = source.readInt()
                if (creditedVerificationCount !in 0..MAX_DAILY_CREDITED_EXERCISE_VERIFICATIONS) {
                    throw CorruptionException("Invalid credited exercise verification count")
                }
                creditedExerciseVerificationIds = List(creditedVerificationCount) { source.readUTF() }
            } else {
                verifiedPushups = Math.addExact(
                    Math.multiplyExact(earnedBudgetRounds, PUSHUPS_PER_SET),
                    pushupRepetitionRemainder,
                )
                plankSecondRemainder = 0L
                machineRepetitionRemainder = 0L
                creditedExerciseVerificationIds = emptyList()
            }
            val counter = DailyCounter(
                localDayKey = localDayKey,
                earnedBudgetRounds = earnedBudgetRounds,
                emergencySkipsUsed = emergencySkipsUsed,
                pushupRepetitionRemainder = pushupRepetitionRemainder,
                spendableBudgetRounds = spendableBudgetRounds,
                eveningResetApplied = eveningResetApplied,
                plankSecondRemainder = plankSecondRemainder,
                machineRepetitionRemainder = machineRepetitionRemainder,
                verifiedPushups = verifiedPushups,
                creditedExerciseVerificationIds = creditedExerciseVerificationIds,
            )
            val metadata = RuntimeMetadata(
                lastCommittedWallMs = source.readLong(),
                recoveryEpochId = source.readUTF(),
                serviceLastConnectedWallMs = source.readLong(),
                lastKnownForegroundClass = source.readEnum<ForegroundClass>(),
            )
            val reports = if (schema >= 2) {
                List(source.readInt()) {
                    val localDayKey = source.readUTF()
                    val protectedUsageMs = source.readLong()
                    source.readLong() // Retired protection-off usage is deliberately discarded.
                    val exerciseSets = if (schema >= 5) source.readLong() else 0L
                    DailyUsageReport(
                        localDayKey = localDayKey,
                        protectedUsageMs = protectedUsageMs,
                        offUsageMs = 0,
                        exerciseSets = exerciseSets,
                    )
                }
            } else emptyList()
            val reminder = if (schema >= 2) {
                OffUsageReminderState(
                    sessionStartedAtWallMs = source.readLong(),
                    qualifyingUsageMs = source.readLong(),
                    firstNotificationSent = source.readBoolean(),
                    lastNotificationWallMs = source.readLong(),
                    usageAtLastNotificationMs = source.readLong(),
                )
            } else OffUsageReminderState()
            val currentVisitGraceActive = if (schema == 2) {
                try {
                    source.readBoolean()
                } catch (_: EOFException) {
                    // Early schema-v2 development builds ended after the reminder state.
                    false
                }
            } else if (schema >= 3) {
                source.readBoolean()
            } else false
            val activityEvents = if (schema >= 6) {
                List(source.readInt()) {
                    ActivityEvent(
                        id = source.readUTF(),
                        localDayKey = source.readUTF(),
                        type = source.readEnum<ActivityEventType>(),
                        startedAtWallMs = source.readLong(),
                        endedAtWallMs = source.readLong(),
                        valueMs = source.readLong(),
                    )
                }
            } else emptyList()
            val monthlyPushupCycles = if (schema >= 9) {
                List(source.readInt()) {
                    MonthlyPushupCycle(
                        cycleStartDate = source.readUTF(),
                        totalPushups = source.readLong(),
                    )
                }
            } else emptyList()
            val state = PersistedState(
                schemaVersion = RESTRICTOR_SCHEMA_VERSION,
                stateRevision = revision,
                settings = settings,
                dailyUsage = daily,
                activeUnlock = unlock,
                pendingWait = pending,
                dailyCounter = counter,
                runtimeMetadata = metadata,
                usageReports = reports,
                activityEvents = activityEvents,
                monthlyPushupCycles = monthlyPushupCycles,
                offUsageReminder = reminder,
                currentVisitGraceActive = currentVisitGraceActive,
            )
            validate(state)
            return state
        } catch (error: CorruptionException) {
            throw error
        } catch (error: Exception) {
            throw CorruptionException("State could not be read", error)
        }
    }

    override suspend fun writeTo(t: PersistedState, output: OutputStream) {
        validate(t)
        val target = DataOutputStream(output)
        target.writeInt(MAGIC)
        target.writeInt(RESTRICTOR_SCHEMA_VERSION)
        target.writeLong(t.stateRevision)
        with(t.settings) {
            target.writeBoolean(enforcementEnabled)
            target.writeBoolean(pauseUntilWallMs != null)
            pauseUntilWallMs?.let(target::writeLong)
            target.writeBoolean(true) // Keep the stable slot while the wait toggle is retired.
            target.writeBoolean(emergencySkipsEnabled)
            target.writeLong(emergencySkipDailyLimit)
            target.writeInt(theme.ordinal)
            target.writeBoolean(onboardingCompleted)
            target.writeInt(sessionMinutes.size)
            sessionMinutes.forEach(target::writeLong)
        }
        target.writeUsage(t.dailyUsage)
        target.writeBoolean(t.activeUnlock != null)
        t.activeUnlock?.let {
            target.writeUTF(it.id)
            target.writeInt(it.source.ordinal)
            target.writeLong(it.grantedAtWallMs)
            target.writeLong(it.expiresAtWallMs)
        }
        target.writeBoolean(t.pendingWait != null)
        t.pendingWait?.let {
            target.writeUTF(it.id)
            target.writeLong(it.selectedDurationMs)
            target.writeLong(it.requiredFocusMs)
            target.writeLong(it.accumulatedFocusMs)
            target.writeLong(it.createdAtWallMs)
            target.writeLong(it.updatedAtWallMs)
        }
        with(t.dailyCounter) {
            target.writeUTF(localDayKey)
            target.writeLong(earnedBudgetRounds)
            target.writeLong(emergencySkipsUsed)
            target.writeLong(pushupRepetitionRemainder)
            target.writeLong(spendableBudgetRounds)
            target.writeBoolean(eveningResetApplied)
            target.writeLong(verifiedPushups)
            target.writeLong(plankSecondRemainder)
            target.writeLong(machineRepetitionRemainder)
            target.writeInt(creditedExerciseVerificationIds.size)
            creditedExerciseVerificationIds.forEach(target::writeUTF)
        }
        with(t.runtimeMetadata) {
            target.writeLong(lastCommittedWallMs)
            target.writeUTF(recoveryEpochId)
            target.writeLong(serviceLastConnectedWallMs)
            target.writeInt(lastKnownForegroundClass.ordinal)
        }
        target.writeInt(t.usageReports.size)
        t.usageReports.forEach {
            target.writeUTF(it.localDayKey)
            target.writeLong(it.protectedUsageMs)
            target.writeLong(0L) // Keep the stable slot while protection-off history is retired.
            target.writeLong(it.exerciseSets)
        }
        with(t.offUsageReminder) {
            target.writeLong(sessionStartedAtWallMs)
            target.writeLong(qualifyingUsageMs)
            target.writeBoolean(firstNotificationSent)
            target.writeLong(lastNotificationWallMs)
            target.writeLong(usageAtLastNotificationMs)
        }
        target.writeBoolean(t.currentVisitGraceActive)
        target.writeInt(t.activityEvents.size)
        t.activityEvents.forEach { event ->
            target.writeUTF(event.id)
            target.writeUTF(event.localDayKey)
            target.writeInt(event.type.ordinal)
            target.writeLong(event.startedAtWallMs)
            target.writeLong(event.endedAtWallMs)
            target.writeLong(event.valueMs)
        }
        target.writeInt(t.monthlyPushupCycles.size)
        t.monthlyPushupCycles.forEach { cycle ->
            target.writeUTF(cycle.cycleStartDate)
            target.writeLong(cycle.totalPushups)
        }
        target.flush()
    }

    private fun validate(state: PersistedState) {
        fun invalid(message: String): Nothing = throw CorruptionException(message)
        if (state.schemaVersion != RESTRICTOR_SCHEMA_VERSION) invalid("Wrong schema")
        if (state.stateRevision < 0) invalid("Negative revision")
        if (!state.settings.enforcementEnabled || state.settings.pauseUntilWallMs != null) {
            invalid("Protection must remain enabled")
        }
        if (!state.settings.emergencySkipsEnabled) invalid("Emergency skips must remain enabled")
        if (!state.settings.delayEnabled) invalid("The focused wait must remain enabled")
        if (state.settings.emergencySkipDailyLimit != EMERGENCY_SKIP_DAILY_LIMIT) {
            invalid("Invalid setting value")
        }
        if (
            state.settings.sessionMinutes.size != DEFAULT_SESSION_MINUTES.size ||
            state.settings.sessionMinutes.any { it !in MIN_SESSION_MINUTES..MAX_SESSION_MINUTES }
        ) invalid("Invalid session presets")
        if (state.dailyUsage.usedMs < 0) invalid("Negative usage")
        state.activeUnlock?.let {
            if (it.id.isBlank() || it.grantedAtWallMs < 0 || it.expiresAtWallMs < it.grantedAtWallMs) {
                invalid("Invalid unlock")
            }
        }
        state.pendingWait?.let {
            if (
                it.id.isBlank() || it.selectedDurationMs <= 0 || it.requiredFocusMs < 0 ||
                it.accumulatedFocusMs !in 0..it.requiredFocusMs || it.createdAtWallMs < 0 ||
                it.updatedAtWallMs < it.createdAtWallMs
            ) invalid("Invalid pending wait")
        }
        if (state.activeUnlock != null && state.pendingWait != null) invalid("Unlock and wait overlap")
        if (
            state.dailyCounter.earnedBudgetRounds !in 0..MAX_SAFE_BUDGET_ROUNDS ||
            state.dailyCounter.spendableBudgetRounds !in 0..state.dailyCounter.earnedBudgetRounds ||
            state.dailyCounter.emergencySkipsUsed < 0 ||
            state.dailyCounter.pushupRepetitionRemainder !in 0 until PUSHUPS_PER_SET ||
            state.dailyCounter.plankSecondRemainder !in 0 until PLANK_SECONDS_PER_SET ||
            state.dailyCounter.machineRepetitionRemainder !in 0 until MACHINE_REPS_PER_SET ||
            state.dailyCounter.verifiedPushups < 0 ||
            state.dailyCounter.creditedExerciseVerificationIds.size >
            MAX_DAILY_CREDITED_EXERCISE_VERIFICATIONS ||
            state.dailyCounter.creditedExerciseVerificationIds.any {
                it.isBlank() || it.length > 128
            } ||
            state.dailyCounter.creditedExerciseVerificationIds.distinct().size !=
            state.dailyCounter.creditedExerciseVerificationIds.size
        ) {
            invalid("Invalid daily counter")
        }
        if (state.usageReports.size > RestrictionEngine.MAX_REPORT_DAYS) invalid("Too many report days")
        state.usageReports.forEach {
            if (
                it.localDayKey.isBlank() || it.protectedUsageMs < 0 || it.offUsageMs != 0L ||
                it.exerciseSets < 0
            ) {
                invalid("Invalid usage report")
            }
        }
        if (state.activityEvents.size > RestrictionEngine.MAX_ACTIVITY_EVENTS) {
            invalid("Too many activity events")
        }
        state.activityEvents.forEach { event ->
            if (
                event.id.isBlank() || event.localDayKey.isBlank() || event.startedAtWallMs < 0 ||
                event.endedAtWallMs < event.startedAtWallMs || event.valueMs < 0
            ) {
                invalid("Invalid activity event")
            }
        }
        var previousCycleStart: String? = null
        state.monthlyPushupCycles.forEach { cycle ->
            val parsedStart = runCatching { java.time.LocalDate.parse(cycle.cycleStartDate) }.getOrNull()
                ?: invalid("Invalid monthly push-up cycle date")
            if (
                parsedStart.dayOfMonth != 9 || cycle.totalPushups < 0 ||
                previousCycleStart?.let { it >= cycle.cycleStartDate } == true
            ) {
                invalid("Invalid monthly push-up cycle")
            }
            previousCycleStart = cycle.cycleStartDate
        }
        with(state.offUsageReminder) {
            if (
                sessionStartedAtWallMs < 0 || qualifyingUsageMs < 0 || lastNotificationWallMs < 0 ||
                usageAtLastNotificationMs < 0 || usageAtLastNotificationMs > qualifyingUsageMs
            ) invalid("Invalid reminder state")
        }
    }

    private fun DataOutputStream.writeUsage(usage: UsagePeriodState) {
        writeUTF(usage.key)
        writeLong(usage.usedMs)
    }

    private fun DataInputStream.readUsage(period: BudgetPeriod) = UsagePeriodState(
        period = period,
        key = readUTF(),
        usedMs = readLong(),
    )

    private inline fun <reified T : Enum<T>> DataInputStream.readEnum(): T {
        val values = enumValues<T>()
        val ordinal = readInt()
        if (ordinal !in values.indices) throw CorruptionException("Invalid enum value")
        return values[ordinal]
    }
}
