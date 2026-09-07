package com.michalkrsik.internetrestrictor.ui.challenge

import com.michalkrsik.internetrestrictor.domain.ExerciseId
import com.michalkrsik.internetrestrictor.domain.ExerciseMetric
import com.michalkrsik.internetrestrictor.domain.ExercisePolicies
import com.michalkrsik.internetrestrictor.domain.MACHINE_REPS_PER_SET
import com.michalkrsik.internetrestrictor.domain.PLANK_SECONDS_PER_SET
import com.michalkrsik.internetrestrictor.domain.PUSHUPS_PER_SET
import org.json.JSONObject

data class ExerciseChallengeSpec(
    val id: ExerciseId,
    val displayName: String,
    val metric: ExerciseMetric,
    val recordingLimitSeconds: Int,
    val unitsPerSet: Int?,
    val verificationReady: Boolean,
    val rewardPolicyReady: Boolean,
) {
    val launchReady: Boolean
        get() = verificationReady && rewardPolicyReady
}

object ExerciseChallengeCatalog {
    val all: List<ExerciseChallengeSpec> = listOf(
        ExerciseChallengeSpec(
            id = ExerciseId.PUSH_UP,
            displayName = "Push-ups",
            metric = ExerciseMetric.REPETITIONS,
            recordingLimitSeconds = 30,
            unitsPerSet = PUSHUPS_PER_SET.toInt(),
            verificationReady = true,
            rewardPolicyReady = true,
        ),
        ExerciseChallengeSpec(
            id = ExerciseId.PLANK,
            displayName = "Plank",
            metric = ExerciseMetric.HOLD_SECONDS,
            recordingLimitSeconds = 30,
            unitsPerSet = PLANK_SECONDS_PER_SET.toInt(),
            verificationReady = true,
            rewardPolicyReady = true,
        ),
        ExerciseChallengeSpec(
            id = ExerciseId.OUTDOOR_LAT_PULLDOWN,
            displayName = "Outdoor lat pull-down",
            metric = ExerciseMetric.REPETITIONS,
            recordingLimitSeconds = 30,
            unitsPerSet = MACHINE_REPS_PER_SET.toInt(),
            verificationReady = true,
            rewardPolicyReady = true,
        ),
        ExerciseChallengeSpec(
            id = ExerciseId.SIT_UP,
            displayName = "Sit-ups",
            metric = ExerciseMetric.REPETITIONS,
            recordingLimitSeconds = 30,
            unitsPerSet = null,
            verificationReady = false,
            rewardPolicyReady = false,
        ),
    )

    const val RECORDING_LIMIT_SECONDS = 30
    const val CAMERA_INSTRUCTION =
        "Frame as much of your body as you can. Imperfect framing and form are okay. " +
            "Tap Start, get ready during the 3-second countdown, then exercise during the 30-second recording."
    const val REWARD_COPY = "Every 5 points earns +3 minutes"
    const val EQUIVALENCE_COPY = "1 point = 1 push-up · 2 machine reps · 3s plank"

    fun find(id: ExerciseId): ExerciseChallengeSpec? = all.firstOrNull { it.id == id }

    fun resolve(wireId: String?): ExerciseChallengeSpec? = ExerciseId.fromWireId(wireId)?.let(::find)
}

internal fun verificationUrl(configuredUrl: String): String =
    configuredUrl.replace(Regex("/v1/pushup-verify/?$"), "/v1/exercise-verify")

data class ExerciseVerificationResult(
    val exerciseId: ExerciseId,
    val metric: ExerciseMetric,
    val value: Int,
    val reason: String,
)

internal fun parseExerciseVerificationResult(body: String): ExerciseVerificationResult {
    val verdict = JSONObject(body)
    val reason = verdict.getString("reason")
    require(reason.length <= 2_000) { "Invalid verification reason" }

    val exerciseId = ExerciseId.fromWireId(verdict.getString("exercise_id"))
        ?: error("Unknown verification exercise")
    val metric = ExerciseMetric.fromWireId(verdict.getString("metric"))
        ?: error("Unknown verification metric")
    require(metric == ExercisePolicies.requiresMetric(exerciseId)) {
        "Verification metric does not match exercise"
    }
    val value = verdict.getInt("value").also {
        require(it >= 0) { "Invalid verification value" }
    }
    if (exerciseId == ExerciseId.UNSUPPORTED) {
        require(value == 0) { "Unsupported clips cannot carry a measured value" }
    }
    return ExerciseVerificationResult(
        exerciseId = exerciseId,
        metric = metric,
        value = value,
        reason = reason,
    )
}
