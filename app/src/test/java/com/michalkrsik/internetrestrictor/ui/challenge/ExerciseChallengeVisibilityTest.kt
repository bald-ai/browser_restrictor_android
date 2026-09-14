package com.michalkrsik.internetrestrictor.ui.challenge

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseChallengeVisibilityTest {
    @After
    fun reset() {
        ExerciseChallengeVisibility.cancelLaunch()
        com.michalkrsik.internetrestrictor.ui.sprint.SprintChallengeVisibility.cancelLaunch()
    }

    @Test
    fun stoppingOneChallengeDoesNotClearTheOtherChallenge() {
        val sprint = com.michalkrsik.internetrestrictor.ui.sprint.SprintChallengeVisibility
        ExerciseChallengeVisibility.onActivityStarted()
        sprint.onActivityStarted()
        ExerciseChallengeVisibility.onActivityStopped()
        assertTrue(sprint.active.value)
        assertTrue(sprint.activityStarted.value)
    }

    @Test
    fun launchPreparationSuppressesGateWithoutClaimingActivityStarted() {
        ExerciseChallengeVisibility.prepareLaunch()

        assertTrue(ExerciseChallengeVisibility.active.value)
        assertFalse(ExerciseChallengeVisibility.activityStarted.value)
    }

    @Test
    fun activityLifecycleOwnsConfirmedVisibility() {
        ExerciseChallengeVisibility.prepareLaunch()
        ExerciseChallengeVisibility.onActivityStarted()

        assertTrue(ExerciseChallengeVisibility.active.value)
        assertTrue(ExerciseChallengeVisibility.activityStarted.value)

        ExerciseChallengeVisibility.onActivityStopped()
        assertFalse(ExerciseChallengeVisibility.active.value)
        assertFalse(ExerciseChallengeVisibility.activityStarted.value)
    }

    @Test
    fun cancelledLaunchRestoresOverlayEligibility() {
        ExerciseChallengeVisibility.prepareLaunch()
        ExerciseChallengeVisibility.cancelLaunch()

        assertFalse(ExerciseChallengeVisibility.active.value)
        assertFalse(ExerciseChallengeVisibility.activityStarted.value)
    }

    @Test
    fun youtubeChallengeStaysOpenAtIntermediateBrowserRewardRounds() {
        assertFalse(shouldReturnAfterExerciseResult(
            returningToYoutube = true,
            youtubeUnlocked = false,
            newlyEarnedRounds = 1,
        ))
        assertTrue(shouldReturnAfterExerciseResult(
            returningToYoutube = true,
            youtubeUnlocked = true,
            newlyEarnedRounds = 1,
        ))
        assertTrue(shouldReturnAfterExerciseResult(
            returningToYoutube = false,
            youtubeUnlocked = false,
            newlyEarnedRounds = 1,
        ))
    }
}
