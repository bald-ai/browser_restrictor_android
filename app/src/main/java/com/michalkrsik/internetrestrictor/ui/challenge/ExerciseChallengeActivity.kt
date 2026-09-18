package com.michalkrsik.internetrestrictor.ui.challenge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.michalkrsik.internetrestrictor.MainActivity
import com.michalkrsik.internetrestrictor.domain.ExerciseId
import com.michalkrsik.internetrestrictor.domain.ExercisePolicies
import com.michalkrsik.internetrestrictor.domain.EXERCISE_SET_REWARD_MINUTES

/** Compatibility entry point for old gate intents; all recordings use selected V1 tasks. */
class ExerciseChallengeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExerciseChallengeVisibility.cancelLaunch()
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_DASHBOARD))
        finish()
    }
}

internal fun formatExerciseResult(
    exerciseId: ExerciseId,
    measuredValue: Int,
    completedSets: Long,
): String {
    val policy = ExercisePolicies.find(exerciseId)
    val detected = "${policy?.resultLabel ?: "Exercise"} detected"
    val measurementText = if (exerciseId == ExerciseId.PLANK) {
        "$measuredValue ${if (measuredValue == 1) "second" else "seconds"}"
    } else {
        "$measuredValue ${if (measuredValue == 1) "repetition" else "repetitions"}"
    }
    return "$detected · $measurementText · +${completedSets * EXERCISE_SET_REWARD_MINUTES} min"
}

internal fun shouldReturnAfterExerciseResult(
    returningToYoutube: Boolean,
    youtubeUnlocked: Boolean,
    newlyEarnedRounds: Long,
): Boolean = shouldReturnAfterChallenge(returningToYoutube, youtubeUnlocked, newlyEarnedRounds)

internal object ExerciseChallengeVisibility : ChallengeVisibility()
