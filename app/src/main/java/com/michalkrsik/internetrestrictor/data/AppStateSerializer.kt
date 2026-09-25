package com.michalkrsik.internetrestrictor.data

import androidx.datastore.core.CorruptionException
import com.michalkrsik.internetrestrictor.domain.ExerciseReceipt
import com.michalkrsik.internetrestrictor.domain.ExerciseId
import com.michalkrsik.internetrestrictor.domain.ExercisePolicies
import androidx.datastore.core.Serializer
import com.michalkrsik.internetrestrictor.domain.ActivityEvent
import com.michalkrsik.internetrestrictor.domain.ActivityEventType
import com.michalkrsik.internetrestrictor.domain.BudgetPeriod
import com.michalkrsik.internetrestrictor.domain.CorrectionEvent
import com.michalkrsik.internetrestrictor.domain.DailyCounter
import com.michalkrsik.internetrestrictor.domain.DailyUsageReport
import com.michalkrsik.internetrestrictor.domain.DEFAULT_SESSION_MINUTES
import com.michalkrsik.internetrestrictor.domain.EMERGENCY_SKIP_DAILY_LIMIT
import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.MAX_DAILY_CREDITED_EXERCISE_VERIFICATIONS
import com.michalkrsik.internetrestrictor.domain.MAX_SESSION_MINUTES
import com.michalkrsik.internetrestrictor.domain.MAX_SAFE_BUDGET_ROUNDS
import com.michalkrsik.internetrestrictor.domain.MIN_SESSION_MINUTES
import com.michalkrsik.internetrestrictor.domain.MonthlyPushupCycle
import com.michalkrsik.internetrestrictor.domain.PendingWait
import com.michalkrsik.internetrestrictor.domain.OffUsageReminderState
import com.michalkrsik.internetrestrictor.domain.PersistedState
import com.michalkrsik.internetrestrictor.domain.EXERCISE_POINT_SIXTHS_PER_BOOST
import com.michalkrsik.internetrestrictor.domain.RESTRICTOR_SCHEMA_VERSION
import com.michalkrsik.internetrestrictor.domain.RestrictionEngine
import com.michalkrsik.internetrestrictor.domain.RestrictorSettings
import com.michalkrsik.internetrestrictor.domain.RuntimeMetadata
import com.michalkrsik.internetrestrictor.domain.SPRINT_SET_QUALIFYING_METERS
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.domain.UnlockSession
import com.michalkrsik.internetrestrictor.domain.UnlockSource
import com.michalkrsik.internetrestrictor.domain.UsagePeriodState
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONObject
import com.michalkrsik.internetrestrictor.domain.AppConfiguration
import com.michalkrsik.internetrestrictor.domain.ConfigurationSettings
import com.michalkrsik.internetrestrictor.domain.validationError
import com.michalkrsik.internetrestrictor.domain.validTaskMeasurement

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
            val settings = readSettings(source, schema)
            val daily = readDailyUsage(source, schema)
            val unlock = readUnlock(source, schema)
            val pending = readPendingWait(source, schema)
            val counter = readDailyCounter(source, schema)
            val metadata = RuntimeMetadata(
                lastCommittedWallMs = source.readLong(),
                recoveryEpochId = source.readUTF(),
                serviceLastConnectedWallMs = source.readLong(),
                lastKnownForegroundClass = source.readEnum<ForegroundClass>(),
            )
            val reports = readUsageReports(source, schema)
            val reminder = readReminder(source, schema)
            val currentVisitGraceActive = readVisitGrace(source, schema)
            val activityEvents = readActivityEvents(source, schema)
            val monthlyPushupCycles = readMonthlyCycles(source, schema)
            val lastExercise = readExerciseReceipt(source, schema)
            val correctionHistory = readCorrectionHistory(source, schema)
            var state = PersistedState(
                lastExercise = lastExercise,
                correctionHistory = correctionHistory,
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
            state = readConfiguration(source, schema, state)
            validate(state)
            return state
        } catch (error: CorruptionException) {
            throw error
        } catch (error: Exception) {
            throw CorruptionException("State could not be read", error)
        }
    }

    private fun readSettings(source: DataInputStream, schema: Int): RestrictorSettings {
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
            if (count !in 1..6 || (schema < 15 && count != DEFAULT_SESSION_MINUTES.size)) {
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
        return settings
    }

    private fun readDailyUsage(source: DataInputStream, schema: Int): UsagePeriodState {
        val daily = source.readUsage(BudgetPeriod.DAILY)
        if (schema <= 2) {
            // Consume the retired weekly and monthly usage records.
            source.readUTF()
            source.readLong()
            source.readUTF()
            source.readLong()
        }
        return daily
    }

    private fun readUnlock(source: DataInputStream, schema: Int): UnlockSession? {
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
        return unlock
    }

    private fun readPendingWait(source: DataInputStream, schema: Int): PendingWait? {
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
        return pending
    }

    private fun readDailyCounter(source: DataInputStream, schema: Int): DailyCounter {
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
        val legacyEveningResetApplied = if (schema >= 8) source.readBoolean() else false
        val verifiedPushups: Long
        val plankSecondRemainder: Long
        val machineRepetitionRemainder: Long
        val creditedExerciseVerificationIds: List<String>
        if (schema >= 10) {
            verifiedPushups = source.readLong()
            plankSecondRemainder = source.readLong()
            machineRepetitionRemainder = source.readLong()
            creditedExerciseVerificationIds = readCreditedIds(source)
        } else {
            verifiedPushups = Math.addExact(
                Math.multiplyExact(earnedBudgetRounds, 5L), // Historical schemas used five push-ups per set.
                pushupRepetitionRemainder,
            )
            plankSecondRemainder = 0L
            machineRepetitionRemainder = 0L
            creditedExerciseVerificationIds = emptyList()
        }
        val counter = DailyCounter(
            localDayKey = localDayKey,
            exercisePointSixths = if (schema >= 11) source.readLong() else 0L,
            earnedBudgetRounds = earnedBudgetRounds,
            emergencySkipsUsed = emergencySkipsUsed,
            pushupRepetitionRemainder = pushupRepetitionRemainder,
            spendableBudgetRounds = spendableBudgetRounds,
            legacyEveningResetApplied = legacyEveningResetApplied,
            plankSecondRemainder = plankSecondRemainder,
            machineRepetitionRemainder = machineRepetitionRemainder,
            verifiedPushups = verifiedPushups,
            creditedExerciseVerificationIds = creditedExerciseVerificationIds,
            sprintMetersRemainder = if (schema >= 12) source.readLong() else 0L,
        )
        return counter
    }

    private fun readCreditedIds(source: DataInputStream): List<String> {
            val creditedVerificationCount = source.readInt()
            if (creditedVerificationCount !in 0..MAX_DAILY_CREDITED_EXERCISE_VERIFICATIONS) {
                throw CorruptionException("Invalid credited exercise verification count")
            }
            return List(creditedVerificationCount) { source.readUTF() }
    }

    private fun readUsageReports(source: DataInputStream, schema: Int): List<DailyUsageReport> {
        val reports = if (schema >= 2) {
            List(source.readBoundedCount("usage reports", RestrictionEngine.MAX_REPORT_DAYS)) {
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
        return reports
    }

    private fun readReminder(source: DataInputStream, schema: Int): OffUsageReminderState {
        val reminder = if (schema >= 2) {
            OffUsageReminderState(
                sessionStartedAtWallMs = source.readLong(),
                qualifyingUsageMs = source.readLong(),
                firstNotificationSent = source.readBoolean(),
                lastNotificationWallMs = source.readLong(),
                usageAtLastNotificationMs = source.readLong(),
            )
        } else OffUsageReminderState()
        return reminder
    }

    private fun readVisitGrace(source: DataInputStream, schema: Int): Boolean {
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
        return currentVisitGraceActive
    }

    private fun readActivityEvents(source: DataInputStream, schema: Int): List<ActivityEvent> {
        val activityEvents = if (schema >= 6) {
            List(source.readBoundedCount("activity events", RestrictionEngine.MAX_ACTIVITY_EVENTS)) {
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
        return activityEvents
    }

    private fun readMonthlyCycles(source: DataInputStream, schema: Int): List<MonthlyPushupCycle> {
        val monthlyPushupCycles = if (schema >= 9) {
            List(source.readBoundedCount("monthly cycles", 10_000)) {
                MonthlyPushupCycle(
                    cycleStartDate = source.readUTF(),
                    totalPushups = source.readLong(),
                )
            }
        } else emptyList()
        return monthlyPushupCycles
    }

    private fun readExerciseReceipt(source: DataInputStream, schema: Int): ExerciseReceipt? {
        val lastExercise = if (schema >= 13 && source.readBoolean()) ExerciseReceipt(
            id = source.readUTF(), dayKey = source.readUTF(), recordedAtWallMs = source.readLong(),
            originalExercise = source.readEnum<ExerciseId>(), originalValue = source.readLong(),
            exercise = source.readEnum<ExerciseId>(), value = source.readLong(),
            pointsBefore = source.readLong(), awardedSets = source.readLong(),
            legacyEveningResetApplied = source.readBoolean(), flagged = source.readBoolean(),
            corrected = source.readBoolean(),
        ) else null
        return lastExercise
    }

    private fun readCorrectionHistory(source: DataInputStream, schema: Int): List<CorrectionEvent> {
        val correctionHistory = if (schema >= 14) {
            // The count is untrusted: reject negatives, then decode incrementally so a
            // corrupt large count fails on EOF instead of preallocating a huge list.
            val count = source.readBoundedCount("correction events", Int.MAX_VALUE)
            val events = ArrayList<CorrectionEvent>()
            repeat(count) {
                events += CorrectionEvent(
                    id = source.readUTF(),
                    receiptId = source.readUTF(),
                    correctedAtWallMs = source.readLong(),
                    originalType = source.readUTF(),
                    originalValue = source.readLong(),
                    correctedType = source.readUTF(),
                    correctedValue = source.readLong(),
                )
            }
            events
        } else emptyList()
        return correctionHistory
    }

    private fun readConfiguration(source: DataInputStream, schema: Int, state: PersistedState): PersistedState {
        if (schema >= 15) {
            val bytes = source.readBoundedCount("v1 state bytes", 32 * 1024 * 1024)
            val encoded = ByteArray(bytes)
            source.readFully(encoded)
            return ConfigurationJson.withStateJson(state, JSONObject(encoded.toString(Charsets.UTF_8)))
        } else {
            return state.copy(configuration = AppConfiguration(settings = ConfigurationSettings(sessionMinutes = state.settings.sessionMinutes)))
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
            target.writeBoolean(legacyEveningResetApplied)
            target.writeLong(verifiedPushups)
            target.writeLong(plankSecondRemainder)
            target.writeLong(machineRepetitionRemainder)
            target.writeInt(creditedExerciseVerificationIds.size)
            creditedExerciseVerificationIds.forEach(target::writeUTF)
            target.writeLong(exercisePointSixths)
            target.writeLong(sprintMetersRemainder)
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
        target.writeBoolean(t.lastExercise != null)
        t.lastExercise?.let {
            target.writeUTF(it.id)
            target.writeUTF(it.dayKey)
            target.writeLong(it.recordedAtWallMs)
            target.writeInt(it.originalExercise.ordinal)
            target.writeLong(it.originalValue)
            target.writeInt(it.exercise.ordinal)
            target.writeLong(it.value)
            target.writeLong(it.pointsBefore)
            target.writeLong(it.awardedSets)
            target.writeBoolean(it.legacyEveningResetApplied)
            target.writeBoolean(it.flagged)
            target.writeBoolean(it.corrected)
        }
        target.writeInt(t.correctionHistory.size)
        t.correctionHistory.forEach {
            target.writeUTF(it.id)
            target.writeUTF(it.receiptId)
            target.writeLong(it.correctedAtWallMs)
            target.writeUTF(it.originalType)
            target.writeLong(it.originalValue)
            target.writeUTF(it.correctedType)
            target.writeLong(it.correctedValue)
        }
        val v1 = ConfigurationJson.stateJson(t).toString().toByteArray(Charsets.UTF_8)
        require(v1.size <= 32 * 1024 * 1024) { "V1 state is too large" }
        target.writeInt(v1.size)
        target.write(v1)
        target.flush()
    }

    private fun invalid(message: String): Nothing = throw CorruptionException(message)

    private fun validate(state: PersistedState) {
        if (state.schemaVersion != RESTRICTOR_SCHEMA_VERSION) invalid("Wrong schema")
        validateExerciseReceipt(state)
        validateTaskAccounting(state)
        validateProtectionSettings(state)
        validateUnlockState(state)
        validateDailyCounter(state)
        validateUsageHistory(state)
        validateActivityHistory(state)
        validateCorrectionHistory(state)
        validateMonthlyCycles(state)
        validateReminder(state)
    }

    private fun validateExerciseReceipt(state: PersistedState) {
        val receipt = state.lastExercise ?: return
        validateExerciseIdentity(receipt)
        validateExerciseAward(receipt)
    }

    private fun validateExerciseIdentity(receipt: ExerciseReceipt) {
        if (receipt.id.isBlank() || receipt.id.length > 128 || receipt.dayKey.isBlank() ||
            receipt.recordedAtWallMs < 0) invalid("Invalid exercise receipt")
    }

    private fun validateExerciseAward(receipt: ExerciseReceipt) {
        if (receipt.originalValue <= 0 || receipt.value < 0 ||
            ExercisePolicies.find(receipt.originalExercise)?.credited != true ||
            ExercisePolicies.find(receipt.exercise)?.credited != true ||
            receipt.pointsBefore !in 0 until EXERCISE_POINT_SIXTHS_PER_BOOST ||
            receipt.awardedSets !in 0..MAX_SAFE_BUDGET_ROUNDS) invalid("Invalid exercise receipt")
    }

    private fun validRemainder(fraction: com.michalkrsik.internetrestrictor.domain.CreditFraction): Boolean =
        fraction.valid() && fraction.whole == 0L

    private fun validateTaskAccounting(state: PersistedState) {
        state.configuration.validationError()?.let { invalid(it) }
        if (state.lastRewardResetWallMs < 0 || state.lastDailyRewardResetWallMs < 0 || !validRemainder(state.taskCreditProgress) ||
            !validRemainder(state.taskProgressBase) || state.taskReceipts.size > 10_000 ||
            state.taskReceipts.map { it.measurement.id }.distinct().size != state.taskReceipts.size ||
            state.dailyCounter.grantedBudgetMinutes?.let { it !in 0..com.michalkrsik.internetrestrictor.domain.MAX_SAFE_MINUTES } == true) invalid("Invalid task accounting")
        state.taskReceipts.forEach {
            if (it.measurement.validationError() != null || !validTaskMeasurement(it.valueText) ||
                java.math.BigDecimal(it.valueText).toLong() != it.value ||
                it.value !in 0..1_000_000 || it.awardedCredits < 0 || it.awardedMinutes < 0) invalid("Invalid saved task receipt")
        }
    }

    private fun validateProtectionSettings(state: PersistedState) {
        if (state.stateRevision < 0) invalid("Negative revision")
        if (!state.settings.enforcementEnabled || state.settings.pauseUntilWallMs != null) {
            invalid("Protection must remain enabled")
        }
        if (!state.settings.emergencySkipsEnabled) invalid("Emergency skips must remain enabled")
        if (!state.settings.delayEnabled) invalid("The focused wait must remain enabled")
        if (state.settings.emergencySkipDailyLimit != EMERGENCY_SKIP_DAILY_LIMIT) {
            invalid("Invalid setting value")
        }
        validateSessionPresets(state.settings)
    }

    private fun validateSessionPresets(settings: RestrictorSettings) {
        if (
            settings.sessionMinutes.size !in 1..6 ||
            settings.sessionMinutes.any { it !in MIN_SESSION_MINUTES..MAX_SESSION_MINUTES }
        ) invalid("Invalid session presets")
    }

    private fun validateUnlockState(state: PersistedState) {
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
    }

    private fun validateDailyCounter(state: PersistedState) {
        val counter = state.dailyCounter
        if (counter.exercisePointSixths !in 0 until EXERCISE_POINT_SIXTHS_PER_BOOST ||
            counter.earnedBudgetRounds !in 0..MAX_SAFE_BUDGET_ROUNDS ||
            counter.spendableBudgetRounds !in 0..counter.earnedBudgetRounds ||
            counter.emergencySkipsUsed < 0 || counter.verifiedPushups < 0) invalid("Invalid daily counter")
        validateLegacyRemainders(counter)
        validateCreditedIds(counter.creditedExerciseVerificationIds)
    }

    private fun validateLegacyRemainders(counter: com.michalkrsik.internetrestrictor.domain.DailyCounter) {
        // Retired fields retain their historical bounds. Preserve 15–17 plank
        // seconds until the next verified plank folds them into credit.
        if (counter.pushupRepetitionRemainder !in 0 until 5L ||
            counter.plankSecondRemainder !in 0 until 18L ||
            counter.machineRepetitionRemainder !in 0 until 10L ||
            counter.sprintMetersRemainder !in 0 until SPRINT_SET_QUALIFYING_METERS) invalid("Invalid daily counter")
    }

    private fun validateCreditedIds(ids: List<String>) {
        if (ids.size > MAX_DAILY_CREDITED_EXERCISE_VERIFICATIONS ||
            ids.any { it.isBlank() || it.length > 128 } ||
            ids.distinct().size != ids.size) invalid("Invalid daily counter")
    }

    private fun validateUsageHistory(state: PersistedState) {
        if (state.usageReports.size > RestrictionEngine.MAX_REPORT_DAYS) invalid("Too many report days")
        state.usageReports.forEach {
            if (
                it.localDayKey.isBlank() || it.protectedUsageMs < 0 || it.offUsageMs != 0L ||
                it.exerciseSets < 0
            ) {
                invalid("Invalid usage report")
            }
        }
    }

    private fun validateActivityHistory(state: PersistedState) {
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
    }

    private fun validShortField(value: String): Boolean = value.isNotBlank() && value.length <= 128

    private fun validateCorrectionHistory(state: PersistedState) {
        state.correctionHistory.forEach {
            if (
                !validShortField(it.id) ||
                !validShortField(it.receiptId) ||
                it.correctedAtWallMs < 0 ||
                !validShortField(it.originalType) ||
                !validShortField(it.correctedType) ||
                it.originalValue <= 0 || it.correctedValue < 0
            ) invalid("Invalid correction event")
        }
    }

    private fun validateMonthlyCycles(state: PersistedState) {
        if (state.monthlyPushupCycles.size > 10_000) invalid("Too many monthly cycles")
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
    }

    private fun validateReminder(state: PersistedState) {
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

private fun DataInputStream.readBoundedCount(label: String, maximum: Int): Int = readInt().also {
    if (it !in 0..maximum) throw CorruptionException("Invalid $label count")
}
