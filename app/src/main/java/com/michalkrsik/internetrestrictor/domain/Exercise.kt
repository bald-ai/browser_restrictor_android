package com.michalkrsik.internetrestrictor.domain

enum class ExerciseId(val wireId: String) {
    PUSH_UP("push_up"),
    PLANK("plank"),
    OUTDOOR_LAT_PULLDOWN("outdoor_lat_pulldown"),
    SIT_UP("sit_up"),
    UNSUPPORTED("unsupported"),
    ;

    companion object {
        fun fromWireId(value: String?): ExerciseId? = entries.firstOrNull { it.wireId == value }
    }
}

enum class ExerciseMetric(val wireId: String) {
    REPETITIONS("repetitions"),
    HOLD_SECONDS("hold_seconds"),
    NONE("none"),
    ;

    companion object {
        fun fromWireId(value: String?): ExerciseMetric? = entries.firstOrNull { it.wireId == value }
    }
}

data class ExercisePolicy(
    val id: ExerciseId,
    val displayName: String,
    val resultLabel: String,
    val metric: ExerciseMetric,
    val unitsPerSet: Long,
    val remainderNounSingular: String,
    val remainderNounPlural: String,
) {
    val credited: Boolean
        get() = unitsPerSet > 0 && metric != ExerciseMetric.NONE
}

object ExercisePolicies {
    val credited: List<ExercisePolicy> = listOf(
        ExercisePolicy(
            id = ExerciseId.PUSH_UP,
            displayName = "Push-ups",
            resultLabel = "Push-ups",
            metric = ExerciseMetric.REPETITIONS,
            unitsPerSet = PUSHUPS_PER_SET,
            remainderNounSingular = "repetition",
            remainderNounPlural = "repetitions",
        ),
        ExercisePolicy(
            id = ExerciseId.PLANK,
            displayName = "Plank",
            resultLabel = "Plank",
            metric = ExerciseMetric.HOLD_SECONDS,
            unitsPerSet = PLANK_SECONDS_PER_SET,
            remainderNounSingular = "second",
            remainderNounPlural = "seconds",
        ),
        ExercisePolicy(
            id = ExerciseId.OUTDOOR_LAT_PULLDOWN,
            displayName = "Outdoor lat pull-down",
            resultLabel = "Machine",
            metric = ExerciseMetric.REPETITIONS,
            unitsPerSet = MACHINE_REPS_PER_SET,
            remainderNounSingular = "repetition",
            remainderNounPlural = "repetitions",
        ),
    )

    fun find(id: ExerciseId): ExercisePolicy? = credited.firstOrNull { it.id == id }

    fun requiresMetric(id: ExerciseId): ExerciseMetric = when (id) {
        ExerciseId.PUSH_UP, ExerciseId.OUTDOOR_LAT_PULLDOWN, ExerciseId.SIT_UP ->
            ExerciseMetric.REPETITIONS
        ExerciseId.PLANK -> ExerciseMetric.HOLD_SECONDS
        ExerciseId.UNSUPPORTED -> ExerciseMetric.NONE
    }
}

data class ExerciseAccumulation(
    val completedSets: Long,
    val remainder: Long,
)

fun accumulateExerciseUnits(
    previousRemainder: Long,
    added: Long,
    unitsPerSet: Long,
): ExerciseAccumulation {
    require(unitsPerSet > 0)
    val accumulated = Math.addExact(previousRemainder, added)
    return ExerciseAccumulation(
        completedSets = accumulated / unitsPerSet,
        remainder = accumulated % unitsPerSet,
    )
}

// Sixth-points keep both half-points (machine reps) and thirds (plank seconds) exact.
const val EXERCISE_POINT_SIXTHS_PER_BOOST = 30L

fun exercisePointSixthsPerUnit(id: ExerciseId): Long = when (id) {
    ExerciseId.PUSH_UP -> 6L
    ExerciseId.OUTDOOR_LAT_PULLDOWN -> 3L
    ExerciseId.PLANK -> 2L
    else -> error("Exercise does not earn points")
}

fun formatExercisePoints(sixths: Long): String =
    java.math.BigDecimal.valueOf(sixths)
        .divide(java.math.BigDecimal.valueOf(6), 2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
