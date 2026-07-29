package com.michalkrsik.internetrestrictor.ui.challenge

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PushupChallengeVisibilityTest {
    @After
    fun reset() {
        PushupChallengeVisibility.cancelLaunch()
    }

    @Test
    fun launchPreparationSuppressesGateWithoutClaimingActivityStarted() {
        PushupChallengeVisibility.prepareLaunch()

        assertTrue(PushupChallengeVisibility.active.value)
        assertFalse(PushupChallengeVisibility.activityStarted.value)
    }

    @Test
    fun activityLifecycleOwnsConfirmedVisibility() {
        PushupChallengeVisibility.prepareLaunch()
        PushupChallengeVisibility.onActivityStarted()

        assertTrue(PushupChallengeVisibility.active.value)
        assertTrue(PushupChallengeVisibility.activityStarted.value)

        PushupChallengeVisibility.onActivityStopped()
        assertFalse(PushupChallengeVisibility.active.value)
        assertFalse(PushupChallengeVisibility.activityStarted.value)
    }

    @Test
    fun cancelledLaunchRestoresOverlayEligibility() {
        PushupChallengeVisibility.prepareLaunch()
        PushupChallengeVisibility.cancelLaunch()

        assertFalse(PushupChallengeVisibility.active.value)
        assertFalse(PushupChallengeVisibility.activityStarted.value)
    }
}
