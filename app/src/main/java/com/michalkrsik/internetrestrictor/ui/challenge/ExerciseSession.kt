package com.michalkrsik.internetrestrictor.ui.challenge

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.michalkrsik.internetrestrictor.AppContainer
import com.michalkrsik.internetrestrictor.BuildConfig
import com.michalkrsik.internetrestrictor.domain.EXERCISE_SET_REWARD_MINUTES
import com.michalkrsik.internetrestrictor.domain.ExerciseId
import com.michalkrsik.internetrestrictor.domain.ExercisePolicies
import com.michalkrsik.internetrestrictor.domain.RewardSaveResult
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_UNLOCK_SETS
import com.michalkrsik.internetrestrictor.domain.formatExercisePoints
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class ChallengePhase { PREPARING, READY, COUNTDOWN, RECORDING, FINALIZING, CHECKING, RESULT, RETRY, ERROR }

internal class ExerciseSession : ViewModel() {
    var phase by mutableStateOf(ChallengePhase.PREPARING)
    var status by mutableStateOf("Preparing camera…")
    var pending: Pair<String, ExerciseVerificationResult>? = null

    fun retrySave(appContainer: AppContainer, returningToYoutube: Boolean) {
        if (phase == ChallengePhase.CHECKING) return
        viewModelScope.launch { savePendingReward(appContainer, returningToYoutube) }
    }

    fun checkVideo(videoFile: File, verificationId: String, appContainer: AppContainer, returningToYoutube: Boolean) {
        phase = ChallengePhase.CHECKING
        status = "Checking exercise…"
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    runCatching {
                        callVerificationServer(
                            videoFile = videoFile,
                            verifyUrl = BuildConfig.VERIFY_URL,
                            sharedSecret = BuildConfig.VERIFY_SHARED_SECRET,
                        )
                    }
                } finally {
                    videoFile.delete()
                }
            }
            result.fold(
                onSuccess = onSuccess@{ verdict ->
                    val policy = ExercisePolicies.find(verdict.exerciseId)
                    if (verdict.exerciseId == ExerciseId.UNSUPPORTED || policy == null) {
                        appContainer.eventLog.record(
                            "Exercise video was unsupported: ${verdict.reason}",
                        )
                        showRetry("No supported exercise detected: ${verdict.reason}")
                        return@onSuccess
                    }
                    if (verdict.value <= 0) {
                        appContainer.eventLog.record(
                            "${policy.displayName} video had no countable ${policy.remainderNounPlural}",
                        )
                        showRetry("No ${policy.displayName.lowercase()} counted: ${verdict.reason}")
                        return@onSuccess
                    }
                    pending = verificationId to verdict
                    savePendingReward(appContainer, returningToYoutube)
                },
                onFailure = { error ->
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Log.e("ExerciseChallenge", "Exercise verification failed", error)
                    appContainer.eventLog.record(
                        "Exercise verification error: ${error.javaClass.simpleName}",
                    )
                    showRetry("Check failed: ${error.message.orEmpty()}")
                },
            )
        }
    }

    private suspend fun savePendingReward(appContainer: AppContainer, returningToYoutube: Boolean) {
        val (verificationId, verdict) = pending ?: return
        val policy = ExercisePolicies.find(verdict.exerciseId) ?: return
        phase = ChallengePhase.CHECKING
        status = "Saving exercise progress…"
        val saved = appContainer.repository.recordVerifiedExercise(verificationId, verdict.exerciseId, verdict.value)
        when (saved) {
            RewardSaveResult.StorageFailure -> {
                showRetry("${policy.displayName} were counted. Retry saving to keep this progress.")
                return
            }
            is RewardSaveResult.Rejected -> {
                pending = null
                showRetry(saved.reason)
                return
            }
            is RewardSaveResult.Saved -> Unit
        }
        pending = null
        val updated = appContainer.repository.snapshot.value
        val newlyEarnedRounds = saved.commit.awardedSets
        val earnedMinutes = newlyEarnedRounds * EXERCISE_SET_REWARD_MINUTES
        val carried = saved.commit.remainder
        appContainer.eventLog.record(
            "${policy.displayName} video measured ${verdict.value}; " +
                "$earnedMinutes daily budget minutes earned; " +
                "${formatExercisePoints(carried)} of 5 points carried",
        )
        val baseResultMessage = formatExerciseResult(
            exerciseId = verdict.exerciseId,
            measuredValue = verdict.value,
            completedSets = newlyEarnedRounds,
        )
        val youtubeUnlocked =
            updated.earnedBudgetRoundsToday >= YOUTUBE_UNLOCK_SETS
        val resultMessage = when {
            returningToYoutube && youtubeUnlocked ->
                "$baseResultMessage · YouTube unlocked until 04:00"
            returningToYoutube ->
                "$baseResultMessage · ${updated.earnedBudgetRoundsToday}/" +
                    "$YOUTUBE_UNLOCK_SETS toward YouTube"
            else -> baseResultMessage
        }
        if (shouldReturnAfterExerciseResult(
            returningToYoutube = returningToYoutube,
            youtubeUnlocked = youtubeUnlocked,
            newlyEarnedRounds = newlyEarnedRounds,
        )) {
            showResultAndReturn(resultMessage)
        } else {
            showRetry(resultMessage)
        }
    }


    private fun showRetry(message: String) { phase = ChallengePhase.RETRY; status = message }
    private fun showResultAndReturn(message: String) { phase = ChallengePhase.RESULT; status = message }
}
