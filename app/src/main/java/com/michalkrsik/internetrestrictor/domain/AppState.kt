package com.michalkrsik.internetrestrictor.domain

import java.util.UUID

data class SupportedBrowser(
    val name: String,
    val packageName: String,
)

const val X_PACKAGE_NAME = "com.twitter.android"

val SUPPORTED_BROWSERS = listOf(
    SupportedBrowser("Chrome", "com.android.chrome"),
    SupportedBrowser("Firefox", "org.mozilla.firefox"),
    SupportedBrowser("Edge", "com.microsoft.emmx"),
    SupportedBrowser("Opera", "com.opera.browser"),
    // X intentionally follows the browser policy and consumes the same shared budget.
    SupportedBrowser("X", X_PACKAGE_NAME),
)

val SUPPORTED_BROWSER_PACKAGES = SUPPORTED_BROWSERS.mapTo(linkedSetOf()) { it.packageName }

const val YOUTUBE_PACKAGE_NAME = "com.google.android.youtube"

enum class ProtectedTarget { BROWSER_POOL, YOUTUBE }

fun isSupportedBrowserPackage(packageName: String?): Boolean =
    packageName != null && packageName in SUPPORTED_BROWSER_PACKAGES

fun protectedTargetForPackage(packageName: String?): ProtectedTarget? = when {
    isSupportedBrowserPackage(packageName) -> ProtectedTarget.BROWSER_POOL
    packageName == YOUTUBE_PACKAGE_NAME -> ProtectedTarget.YOUTUBE
    else -> null
}

fun isProtectedTargetPackage(packageName: String?): Boolean =
    protectedTargetForPackage(packageName) != null
const val RESTRICTOR_SCHEMA_VERSION = 15
const val MINUTE_MS = 60_000L
const val HOUR_MS = 60L * MINUTE_MS
const val DAY_MS = 24L * HOUR_MS
const val PENDING_WAIT_EXPIRY_MS = DAY_MS
const val EMERGENCY_UNLOCK_MS = 15L * MINUTE_MS
const val DAILY_BASE_BUDGET_MINUTES = 0L
const val EXERCISE_SET_REWARD_MINUTES = 3L
val PUSHUPS_PER_SET get() = ExercisePolicies.find(ExerciseId.PUSH_UP)!!.unitsPerSet
val PLANK_SECONDS_PER_SET get() = ExercisePolicies.find(ExerciseId.PLANK)!!.unitsPerSet
val MACHINE_REPS_PER_SET get() = ExercisePolicies.find(ExerciseId.OUTDOOR_LAT_PULLDOWN)!!.unitsPerSet
const val SPRINT_INTENDED_METERS = 150L
const val SPRINT_SET_QUALIFYING_METERS = 120L
const val MIN_SPRINT_AVERAGE_SPEED_KMH = 15.0
const val YOUTUBE_UNLOCK_SETS = 4L
const val MAX_DAILY_CREDITED_EXERCISE_VERIFICATIONS = 256
const val EMERGENCY_SKIP_DAILY_LIMIT = 1L
const val DAILY_RESET_HOUR = 4
const val MIN_SESSION_MINUTES = 1L
const val MAX_SESSION_MINUTES = 120L
val DEFAULT_SESSION_MINUTES = listOf(3L, 5L, 10L)
const val FIXED_WAIT_MS = 12_000L
const val MAX_SAFE_MINUTES = Long.MAX_VALUE / MINUTE_MS
const val MAX_SAFE_BUDGET_ROUNDS =
    (MAX_SAFE_MINUTES - DAILY_BASE_BUDGET_MINUTES) / EXERCISE_SET_REWARD_MINUTES

enum class ThemePreference { DARK, LIGHT }

enum class BudgetPeriod { DAILY }

enum class UnlockSource { NORMAL, EMERGENCY }

enum class ForegroundClass { BROWSER, RESTRICTOR, OTHER }

enum class ActivityEventType { EXERCISE_SET, BROWSER_SESSION }

enum class GateMode {
    SERVICE_UNAVAILABLE,
    ENFORCEMENT_DISABLED,
    BROWSER_BACKGROUND,
    PAUSED,
    WAITING,
    ACCESS_GRANTED_NORMAL,
    ACCESS_GRANTED_EMERGENCY,
    ACCESS_GRANTED_CURRENT_VISIT,
    OUT_OF_TIME,
    OUT_OF_TIME_LATCHED,
    YOUTUBE_LOCKED,
    YOUTUBE_ACCESS_GRANTED,
    STORAGE_ERROR,
}

enum class ProtectionHealth {
    READY,
    READY_LIMITED_RECOVERY,
    ENFORCEMENT_OFF,
    ACCESSIBILITY_REQUIRED,
    USAGE_ACCESS_RECOMMENDED,
    SERVICE_DISCONNECTED,
    BROWSERS_NOT_FOUND,
    STORAGE_ERROR,
    RESTRICTED_SETTINGS_BLOCKED,
    ADVANCED_PROTECTION_BLOCKED,
}

data class RestrictorSettings(
    val enforcementEnabled: Boolean = true,
    val pauseUntilWallMs: Long? = null,
    val delayEnabled: Boolean = true,
    val emergencySkipsEnabled: Boolean = true,
    val emergencySkipDailyLimit: Long = EMERGENCY_SKIP_DAILY_LIMIT,
    val theme: ThemePreference = ThemePreference.DARK,
    val onboardingCompleted: Boolean = false,
    val sessionMinutes: List<Long> = DEFAULT_SESSION_MINUTES,
)

data class DailyUsageReport(
    val localDayKey: String,
    val protectedUsageMs: Long = 0,
    val offUsageMs: Long = 0,
    val exerciseSets: Long = 0,
)

data class ActivityEvent(
    val id: String,
    val localDayKey: String,
    val type: ActivityEventType,
    val startedAtWallMs: Long,
    val endedAtWallMs: Long,
    val valueMs: Long,
)

data class OffUsageReminderState(
    val sessionStartedAtWallMs: Long = 0,
    val qualifyingUsageMs: Long = 0,
    val firstNotificationSent: Boolean = false,
    val lastNotificationWallMs: Long = 0,
    val usageAtLastNotificationMs: Long = 0,
)

data class UsagePeriodState(
    val period: BudgetPeriod,
    val key: String = "",
    val usedMs: Long = 0,
)

data class UnlockSession(
    val id: String,
    val source: UnlockSource,
    val grantedAtWallMs: Long,
    val expiresAtWallMs: Long,
)

data class PendingWait(
    val id: String,
    val selectedDurationMs: Long,
    val requiredFocusMs: Long,
    val accumulatedFocusMs: Long,
    val createdAtWallMs: Long,
    val updatedAtWallMs: Long,
)

data class DailyCounter(
    val localDayKey: String = "",
    val earnedBudgetRounds: Long = 0,
    val emergencySkipsUsed: Long = 0,
    // Legacy per-exercise progress, consumed once by normalization.
    val exercisePointSixths: Long = 0,
    val pushupRepetitionRemainder: Long = 0,
    val spendableBudgetRounds: Long = 0,
    // Inert compatibility marker for old saved balances and exercise receipts.
    val legacyEveningResetApplied: Boolean = false,
    val plankSecondRemainder: Long = 0,
    val machineRepetitionRemainder: Long = 0,
    val verifiedPushups: Long = 0,
    val creditedExerciseVerificationIds: List<String> = emptyList(),
    val sprintMetersRemainder: Long = 0,
    // Null keeps old installations' fixed three-minute accounting until v1 is applied.
    val grantedBudgetMinutes: Long? = null,
)

data class MonthlyPushupCycle(
    val cycleStartDate: String,
    val totalPushups: Long = 0,
)

data class RuntimeMetadata(
    val lastCommittedWallMs: Long = 0,
    val recoveryEpochId: String = "",
    val serviceLastConnectedWallMs: Long = 0,
    val lastKnownForegroundClass: ForegroundClass = ForegroundClass.OTHER,
)

data class ExerciseReceipt(
    val id: String,
    val dayKey: String,
    val recordedAtWallMs: Long,
    val originalExercise: ExerciseId,
    val originalValue: Long,
    val exercise: ExerciseId = originalExercise,
    val value: Long = originalValue,
    val pointsBefore: Long,
    val awardedSets: Long,
    val legacyEveningResetApplied: Boolean,
    val flagged: Boolean = false,
    val corrected: Boolean = false,
)

fun ExerciseReceipt.canCorrect(counter: DailyCounter): Boolean =
    originalValue > 0 && ExercisePolicies.find(originalExercise)?.credited == true &&
        dayKey == counter.localDayKey && legacyEveningResetApplied == counter.legacyEveningResetApplied &&
        id in counter.creditedExerciseVerificationIds

/**
 * Immutable record of one saved correction that actually changed the count or the
 * validation type. Original fields always mean the reviewer's measurement, never the
 * previous manual value. Types are stable wire identifiers (ExerciseId.wireId today)
 * so other validation kinds can reuse the entry later.
 */
data class CorrectionEvent(
    val id: String,
    val receiptId: String,
    val correctedAtWallMs: Long,
    val originalType: String,
    val originalValue: Long,
    val correctedType: String,
    val correctedValue: Long,
)

data class PersistedState(
    val schemaVersion: Int = RESTRICTOR_SCHEMA_VERSION,
    val lastExercise: ExerciseReceipt? = null,
    val stateRevision: Long = 0,
    val settings: RestrictorSettings = RestrictorSettings(),
    val dailyUsage: UsagePeriodState = UsagePeriodState(BudgetPeriod.DAILY),
    val activeUnlock: UnlockSession? = null,
    val pendingWait: PendingWait? = null,
    val dailyCounter: DailyCounter = DailyCounter(),
    val runtimeMetadata: RuntimeMetadata = RuntimeMetadata(),
    val usageReports: List<DailyUsageReport> = emptyList(),
    val activityEvents: List<ActivityEvent> = emptyList(),
    val monthlyPushupCycles: List<MonthlyPushupCycle> = emptyList(),
    val offUsageReminder: OffUsageReminderState = OffUsageReminderState(),
    val currentVisitGraceActive: Boolean = false,
    val correctionHistory: List<CorrectionEvent> = emptyList(),
    val configuration: AppConfiguration = AppConfiguration(),
    val hasAppliedServerConfiguration: Boolean = false,
    val taskReceipts: List<SavedTaskReceipt> = emptyList(),
    val taskCreditProgress: CreditFraction = CreditFraction(),
    val taskProgressBase: CreditFraction = CreditFraction(),
    val taskBudgetKey: String = "",
    val lastRewardResetWallMs: Long = 0,
    val lastDailyRewardResetWallMs: Long = 0,
)

data class RuntimeSignals(
    val foregroundClass: ForegroundClass = ForegroundClass.OTHER,
    val activeTarget: ProtectedTarget? = null,
    val gateFocused: Boolean = false,
    val screenInteractive: Boolean = true,
    val keyguardUnlocked: Boolean = true,
    val serviceConnected: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val usageRecoveryOperational: Boolean = false,
    val supportedBrowserInstalled: Boolean = true,
    val advancedProtectionEnabled: Boolean = false,
    val storageReady: Boolean = false,
    val storageError: Boolean = false,
    val storageWriteError: Boolean = false,
    val gateSessionId: String? = null,
    val outOfTimeLatchToken: String? = null,
    val latchedBlockers: Set<BudgetPeriod> = emptySet(),
)

data class BudgetSnapshot(
    val period: BudgetPeriod,
    val enabled: Boolean,
    val limitMinutes: Long,
    val usedMs: Long,
    val remainingMs: Long?,
    val percentage: Int,
    val exhausted: Boolean,
)

data class WaitSnapshot(
    val pendingWaitId: String,
    val selectedDurationMs: Long,
    val requiredFocusMs: Long,
    val accumulatedFocusMs: Long,
    val remainingFocusMs: Long,
    val progress: Float,
)

data class SkipSnapshot(
    val enabled: Boolean,
    val limit: Long,
    val used: Long,
    val remaining: Long,
    val grantDurationMs: Long,
    val eligible: Boolean,
)

data class StateSnapshot(
    val stateRevision: Long,
    val canonicalMode: GateMode,
    val gateSessionId: String?,
    val outOfTimeLatchToken: String?,
    val pendingWaitId: String?,
    val activeUnlockId: String?,
    val activeTarget: ProtectedTarget?,
    val settings: RestrictorSettings,
    val budgetSnapshots: List<BudgetSnapshot>,
    val skipSnapshot: SkipSnapshot,
    val waitSnapshot: WaitSnapshot?,
    val enforcementHealth: ProtectionHealth,
    val accessibilityEnabled: Boolean,
    val serviceConnected: Boolean,
    val usageAccessGranted: Boolean,
    val supportedBrowserInstalled: Boolean,
    val activeUnlock: UnlockSession?,
    val earnedBudgetRoundsToday: Long,
    val exercisePointSixthsToday: Long,
    val verifiedPushupsToday: Long,
    val sprintMetersRemainder: Long,
    val offSessionStartedAtWallMs: Long,
    val usageReports: List<DailyUsageReport>,
    val todayUsageReport: DailyUsageReport,
    val currentWeekUsageReports: List<DailyUsageReport>,
    val currentMonthlyPushupCycle: MonthlyPushupCycle,
    val completedMonthlyPushupCycles: List<MonthlyPushupCycle>,
    val todayActivityEvents: List<ActivityEvent>,
    val latchedBlockers: Set<BudgetPeriod>,
    val wallNowMs: Long,
    val isLoaded: Boolean,
    val lastErrorMessage: String? = null,
    val lastExercise: ExerciseReceipt? = null,
    val canCorrectLastExercise: Boolean = false,
    val configuration: AppConfiguration = AppConfiguration(),
    val taskReceipts: List<SavedTaskReceipt> = emptyList(),
    val taskCreditProgress: Double = 0.0,
    val hasAppliedServerConfiguration: Boolean = false,
    val recentActivityEvents: List<ActivityEvent> = emptyList(),
    val recentCorrectionEvents: List<CorrectionEvent> = emptyList(),
    val lastRewardResetWallMs: Long = 0,
    val modifiableTaskReceiptIds: Set<String> = emptySet(),
    val lastDailyRewardResetWallMs: Long = 0,
) {
    val appliedConfigRevision: Long get() = if (hasAppliedServerConfiguration) configuration.revision else -1
    val usesFullGate: Boolean get() = canonicalMode.traits.overlay == ModeOverlay.GATE
    val usesBadge: Boolean get() = canonicalMode.traits.overlay == ModeOverlay.BADGE
}

enum class ModeOverlay { NONE, GATE, BADGE }
data class ModeTraits(
    val overlay: ModeOverlay = ModeOverlay.NONE,
    val recordsUsage: Boolean = false,
    val chargesBudget: Boolean = false,
    val grantsAccess: Boolean = false,
    val gateSession: Boolean = false,
)

val GateMode.traits: ModeTraits get() = when (this) {
    GateMode.PAUSED, GateMode.WAITING, GateMode.OUT_OF_TIME,
    GateMode.OUT_OF_TIME_LATCHED, GateMode.YOUTUBE_LOCKED ->
        ModeTraits(overlay = ModeOverlay.GATE, gateSession = true)
    GateMode.ACCESS_GRANTED_NORMAL ->
        ModeTraits(ModeOverlay.BADGE, recordsUsage = true, chargesBudget = true, grantsAccess = true)
    GateMode.ACCESS_GRANTED_EMERGENCY ->
        ModeTraits(ModeOverlay.BADGE, recordsUsage = true, grantsAccess = true)
    GateMode.ACCESS_GRANTED_CURRENT_VISIT -> ModeTraits(ModeOverlay.BADGE, grantsAccess = true)
    GateMode.YOUTUBE_ACCESS_GRANTED, GateMode.ENFORCEMENT_DISABLED -> ModeTraits(grantsAccess = true)
    GateMode.SERVICE_UNAVAILABLE, GateMode.BROWSER_BACKGROUND, GateMode.STORAGE_ERROR -> ModeTraits()
}

sealed interface CommandResult {
    data class Applied(val state: PersistedState, val reward: RewardCommit? = null) : CommandResult
    data class Rejected(val state: PersistedState, val reason: String) : CommandResult
    data class NormalCompletionWon(val state: PersistedState) : CommandResult
}

fun interface IdGenerator {
    fun nextId(): String
}

object RandomIdGenerator : IdGenerator {
    override fun nextId(): String = UUID.randomUUID().toString()
}

/** The award produced by this receipt, independent of subsequent day resets or other rewards. */
data class RewardCommit(val awardedSets: Long, val remainder: Long, val dayKey: String)

sealed interface RewardSaveResult {
    data class Saved(val commit: RewardCommit) : RewardSaveResult
    data class Rejected(val reason: String) : RewardSaveResult
    data object StorageFailure : RewardSaveResult
}
