package com.michalkrsik.internetrestrictor.ui.challenge

import org.json.JSONObject

enum class ExerciseId(val wireId: String) {
    PUSH_UP("push_up"),
    PLANK("plank"),
    OUTDOOR_LAT_PULLDOWN("outdoor_lat_pulldown"),
    SIT_UP("sit_up"),
    ;

    companion object {
        fun fromWireId(value: String?): ExerciseId? = entries.firstOrNull { it.wireId == value }
    }
}

enum class ExerciseMetric(val wireId: String) {
    REPETITIONS("repetitions"),
    HOLD_SECONDS("hold_seconds"),
    ;

    companion object {
        fun fromWireId(value: String?): ExerciseMetric? = entries.firstOrNull { it.wireId == value }
    }
}

data class ExerciseChallengeSpec(
    val id: ExerciseId,
    val displayName: String,
    val metric: ExerciseMetric,
    val recordingLimitSeconds: Int,
    val minimumQualifyingValue: Int?,
    val verificationReady: Boolean,
    val rewardPolicyReady: Boolean,
    val rewardHeadline: String?,
    val cameraInstruction: String?,
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
            minimumQualifyingValue = 5,
            verificationReady = true,
            rewardPolicyReady = true,
            rewardHeadline = "Every 5 push-ups · +3 minutes today",
            cameraInstruction = "Frame as much of your body as you can. Imperfect framing and form are okay. " +
                "Tap Start and do as many push-ups as you want before the 30-second countdown ends.",
        ),
        ExerciseChallengeSpec(
            id = ExerciseId.PLANK,
            displayName = "Plank",
            metric = ExerciseMetric.HOLD_SECONDS,
            recordingLimitSeconds = 30,
            minimumQualifyingValue = 25,
            verificationReady = false,
            rewardPolicyReady = false,
            rewardHeadline = null,
            cameraInstruction = null,
        ),
        ExerciseChallengeSpec(
            id = ExerciseId.OUTDOOR_LAT_PULLDOWN,
            displayName = "Outdoor lat pull-down",
            metric = ExerciseMetric.REPETITIONS,
            recordingLimitSeconds = 30,
            minimumQualifyingValue = null,
            verificationReady = false,
            rewardPolicyReady = false,
            rewardHeadline = null,
            cameraInstruction = null,
        ),
        ExerciseChallengeSpec(
            id = ExerciseId.SIT_UP,
            displayName = "Sit-ups",
            metric = ExerciseMetric.REPETITIONS,
            recordingLimitSeconds = 30,
            minimumQualifyingValue = null,
            verificationReady = false,
            rewardPolicyReady = false,
            rewardHeadline = null,
            cameraInstruction = null,
        ),
    )

    val default: ExerciseChallengeSpec = requireNotNull(find(ExerciseId.PUSH_UP))

    fun find(id: ExerciseId): ExerciseChallengeSpec? = all.firstOrNull { it.id == id }

    fun resolve(wireId: String?): ExerciseChallengeSpec? = ExerciseId.fromWireId(wireId)?.let(::find)
}

internal fun verificationUrlForExercise(configuredUrl: String, exerciseId: ExerciseId): String {
    if (exerciseId == ExerciseId.PUSH_UP) return configuredUrl
    return configuredUrl.replace(Regex("/v1/pushup-verify/?$"), "/v1/exercise-verify")
}

data class ExerciseVerificationResult(
    val exerciseId: ExerciseId,
    val metric: ExerciseMetric,
    val value: Int,
    val isSuccess: Boolean,
    val confidence: Double,
    val reason: String,
)

internal fun parseExerciseVerificationResult(
    body: String,
    expected: ExerciseChallengeSpec,
): ExerciseVerificationResult {
    val verdict = JSONObject(body)
    val confidence = verdict.getDouble("confidence").also {
        require(it in 0.0..1.0) { "Invalid verification confidence" }
    }
    val reason = verdict.getString("reason")
    require(reason.length <= 2_000) { "Invalid verification reason" }
    val isSuccess = verdict.getBoolean("is_success")

    if (!verdict.has("exercise_id")) {
        require(expected.id == ExerciseId.PUSH_UP) { "Legacy response is valid only for push-ups" }
        val repetitions = verdict.getInt("repetition_count").also {
            require(it >= 0) { "Invalid verification count" }
        }
        return ExerciseVerificationResult(
            exerciseId = ExerciseId.PUSH_UP,
            metric = ExerciseMetric.REPETITIONS,
            value = repetitions,
            isSuccess = isSuccess,
            confidence = confidence,
            reason = reason,
        )
    }

    val exerciseId = ExerciseId.fromWireId(verdict.getString("exercise_id"))
    require(exerciseId == expected.id) { "Verification exercise does not match request" }
    val metric = ExerciseMetric.fromWireId(verdict.getString("metric"))
    require(metric == expected.metric) { "Verification metric does not match exercise" }
    val value = verdict.getInt("value").also {
        require(it >= 0) { "Invalid verification value" }
    }
    return ExerciseVerificationResult(
        exerciseId = exerciseId,
        metric = metric,
        value = value,
        isSuccess = isSuccess,
        confidence = confidence,
        reason = reason,
    )
}
