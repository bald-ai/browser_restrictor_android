package com.michalkrsik.internetrestrictor.domain

import java.math.BigInteger
import java.math.BigDecimal
import java.time.LocalTime

/** Server-owned editable rules. Local balances are deliberately absent. */
data class AppConfiguration(
    val revision: Long = 0,
    val settings: ConfigurationSettings = ConfigurationSettings(),
    val tasks: List<TaskDefinition> = STARTER_TASKS,
) {
    fun validationError(): String? = when {
        revision < 0 -> "Configuration revision cannot be negative"
        settings.validationError() != null -> settings.validationError()
        tasks.size > 50 -> "At most 50 tasks are supported"
        tasks.map { it.id }.distinct().size != tasks.size -> "Task IDs must be unique"
        else -> tasks.firstNotNullOfOrNull { it.validationError() }
    }
}

data class ConfigurationSettings(
    val waitSeconds: Long = 12,
    val minutesPerCredit: Long = 3,
    val dailyBudgetMinutes: Long = 0,
    val sessionMinutes: List<Long> = DEFAULT_SESSION_MINUTES,
    val dayReset: String = "04:00",
    val youtubeCredits: Long = 4,
    val emergencyMinutes: Long = 15,
    val creditName: String = "credits",
    val unlockLabel: String = "Unlock",
    val youtubeUnlockLabel: String = "Unlock YouTube",
    val emergencyUnlockLabel: String = "Emergency unlock",
) {
    fun validationError(): String? = when {
        waitSeconds !in 0..600 -> "Wait must be between 0 and 600 seconds"
        minutesPerCredit !in 1..120 -> "Credit reward must be between 1 and 120 minutes"
        sessionMinutes.size != 3 || sessionMinutes.any { it !in MIN_SESSION_MINUTES..MAX_SESSION_MINUTES } -> "Choose three session lengths from 1 to 120 minutes"
        dailyBudgetMinutes !in 0..30 -> "Daily budget must be between 0 and 30 minutes"
        !validResetTime(dayReset) -> "Reset times must use HH:mm"
        youtubeCredits !in 1..100 -> "YouTube credit requirement is outside its supported range"
        emergencyMinutes !in 1..120 -> "Emergency duration must be between 1 and 120 minutes"
        listOf(creditName, unlockLabel, youtubeUnlockLabel, emergencyUnlockLabel).any { it.isBlank() || it.length > 60 } -> "Labels must contain between 1 and 60 characters"
        else -> null
    }
}

fun validResetTime(value: String): Boolean = Regex("(?:[01][0-9]|2[0-3]):[0-5][0-9]").matches(value)
fun resetTime(value: String): LocalTime = LocalTime.parse(value)

data class TaskDefinition(
    val id: String,
    val name: String,
    val verifier: String,
    val metric: String,
    val unitsPerCredit: Long,
    val correctionMinimum: Long,
    val instructions: String,
    val paused: Boolean = false,
    val gpsMode: String? = null,
) {
    fun validationError(): String? = when {
        !Regex("[a-z][a-z0-9_-]{0,63}").matches(id) -> "Invalid task ID"
        name.isBlank() || name.length > 80 -> "Task name must contain 1 to 80 characters"
        verifier !in setOf("video", "gps") -> "Choose video or GPS verification"
        metric !in setOf("repetitions", "seconds", "meters") -> "Unsupported measurement"
        verifier == "gps" && (metric != "meters" || gpsMode !in setOf("walk", "sprint")) -> "GPS tasks must measure walking or sprinting distance"
        verifier == "video" && (metric == "meters" || gpsMode != null) -> "Video tasks measure repetitions or seconds"
        unitsPerCredit !in 1..100_000 -> "Units per credit must be between 1 and 100,000"
        correctionMinimum !in 1..100_000 -> "Correction minimum must be between 1 and 100,000"
        verifier == "video" && metric == "seconds" && correctionMinimum > 30 -> "A video correction requires evidence within the 30-second recording limit"
        verifier == "video" && metric == "repetitions" && correctionMinimum > 150 -> "Video repetition correction minimum cannot exceed 150"
        instructions.isBlank() || instructions.length > 2000 -> "Task needs judging instructions of at most 2,000 characters"
        else -> null
    }
}

val STARTER_TASKS = listOf(
    TaskDefinition("pushups", "Push-ups", "video", "repetitions", 3, 1, "Count complete push-ups with visible lowering and raising of the body. Count only observable repetitions."),
    TaskDefinition("plank", "Plank", "video", "seconds", 10, 1, "Measure seconds holding a plank with the body supported on toes and forearms or hands, with a reasonably straight torso."),
    TaskDefinition("sprint", "Sprint", "gps", "meters", 50, 1, "Measure qualifying sprint distance using the provided GPS speed and accuracy checks.", gpsMode = "sprint"),
    TaskDefinition("walk", "Walk", "gps", "meters", 100, 1, "Measure qualifying walking distance, excluding poor readings, stationary drift, gaps and vehicle-speed sections.", gpsMode = "walk"),
)

/** A server-verifiable measurement, with the rules frozen when recording started. */
data class TaskMeasurementReceipt(
    val id: String,
    val taskId: String,
    val configRevision: Long,
    val task: TaskDefinition,
    val value: Long,
    val startedAtWallMs: Long,
    val finishedAtWallMs: Long,
    val minutesPerCredit: Long = 3,
    val dayReset: String = "04:00",
    val verified: Boolean = true,
    val correctionEligible: Boolean = true,
    val rewardEligible: Boolean = true,
    val timeZone: String = "Europe/Prague",
    val attemptId: String = id,
    val fallbackEligible: Boolean = false,
    val manualCompletion: Boolean = false,
    val correctedUnits: Long? = null,
    val measuredUnitsText: String = value.toString(),
    val correctedUnitsText: String? = correctedUnits?.toString(),
    val maximumCorrectableUnits: Double? = null,
    val minutesRewardEligible: Boolean = rewardEligible,
)

data class SavedTaskReceipt(
    val measurement: TaskMeasurementReceipt,
    val value: Long = measurement.value,
    val dayKey: String,
    val budgetKey: String,
    val rewardEligible: Boolean,
    val awardedCredits: Long = 0,
    val awardedMinutes: Long = 0,
    val corrected: Boolean = false,
    val flagged: Boolean = false,
    val valueText: String = value.toString(),
    val minutesRewardEligible: Boolean = rewardEligible && measurement.minutesRewardEligible,
)

/** Exact fractions prevent three thirds or mixed task requirements losing a credit. */
data class CreditFraction(val numerator: String = "0", val denominator: String = "1") {
    fun plus(value: Long, units: Long): CreditFraction {
        return plus(value.toString(), units)
    }
    fun plus(value: String, units: Long): CreditFraction {
        val a = BigInteger(numerator); val b = BigInteger(denominator)
        val decimal = BigDecimal(value).stripTrailingZeros()
        val scale = BigInteger.TEN.pow(decimal.scale().coerceAtLeast(0))
        val n = if (decimal.scale() < 0) decimal.toBigIntegerExact() else decimal.unscaledValue()
        return of(a * units.toBigInteger() * scale + n * b, b * units.toBigInteger() * scale)
    }
    // String conversion is checked on Android 10/11 too; BigInteger.longValueExact
    // is unavailable before API 31 even though the JVM unit-test runtime has it.
    val whole: Long get() = (BigInteger(numerator) / BigInteger(denominator)).toString().toLong()
    fun remainder(): CreditFraction = of(BigInteger(numerator) % BigInteger(denominator), BigInteger(denominator))
    val approximate: Double get() = BigInteger(numerator).toDouble() / BigInteger(denominator).toDouble()
    fun valid(): Boolean = runCatching { numerator.length <= 4096 && denominator.length <= 4096 && BigInteger(numerator).signum() >= 0 && BigInteger(denominator).signum() > 0 }.getOrDefault(false)
    companion object {
        private fun of(n: BigInteger, d: BigInteger): CreditFraction {
            val divisor = n.gcd(d)
            return CreditFraction((n / divisor).toString(), (d / divisor).toString())
        }
    }
}
