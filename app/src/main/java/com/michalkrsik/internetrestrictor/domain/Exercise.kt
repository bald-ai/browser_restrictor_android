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
    val pointSixthsPerUnit: Long,
    val remainderNounSingular: String,
    val remainderNounPlural: String,
) {
    init { require(pointSixthsPerUnit > 0) }

    val unitsPerSet: Long get() = EXERCISE_POINT_SIXTHS_PER_BOOST / pointSixthsPerUnit

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
            pointSixthsPerUnit = 6L,
            remainderNounSingular = "repetition",
            remainderNounPlural = "repetitions",
        ),
        ExercisePolicy(
            id = ExerciseId.PLANK,
            displayName = "Plank",
            resultLabel = "Plank",
            metric = ExerciseMetric.HOLD_SECONDS,
            pointSixthsPerUnit = 2L,
            remainderNounSingular = "second",
            remainderNounPlural = "seconds",
        ),
        ExercisePolicy(
            id = ExerciseId.OUTDOOR_LAT_PULLDOWN,
            displayName = "Outdoor lat pull-down",
            resultLabel = "Machine",
            metric = ExerciseMetric.REPETITIONS,
            pointSixthsPerUnit = 3L,
            remainderNounSingular = "repetition",
            remainderNounPlural = "repetitions",
        ),
    )

    fun find(id: ExerciseId): ExercisePolicy? = credited.firstOrNull { it.id == id }

    fun requiresMetric(id: ExerciseId): ExerciseMetric = find(id)?.metric ?: when (id) {
        ExerciseId.SIT_UP -> ExerciseMetric.REPETITIONS
        ExerciseId.UNSUPPORTED -> ExerciseMetric.NONE
        else -> error("Missing exercise policy for $id")
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

fun exercisePointSixthsPerUnit(id: ExerciseId): Long =
    requireNotNull(ExercisePolicies.find(id)) { "Exercise does not earn points" }.pointSixthsPerUnit

fun formatExercisePoints(sixths: Long): String =
    java.math.BigDecimal.valueOf(sixths)
        .divide(java.math.BigDecimal.valueOf(6), 2, java.math.RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
