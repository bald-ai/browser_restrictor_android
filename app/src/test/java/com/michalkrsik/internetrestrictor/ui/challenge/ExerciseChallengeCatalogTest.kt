package com.michalkrsik.internetrestrictor.ui.challenge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseChallengeCatalogTest {
    @Test
    fun catalogUsesStableWireIdsAndMetricTypes() {
        assertEquals(
            listOf("push_up", "plank", "outdoor_lat_pulldown", "sit_up"),
            ExerciseChallengeCatalog.all.map { it.id.wireId },
        )
        assertEquals(ExerciseMetric.HOLD_SECONDS, ExerciseChallengeCatalog.resolve("plank")?.metric)
        assertEquals(
            ExerciseMetric.REPETITIONS,
            ExerciseChallengeCatalog.resolve("outdoor_lat_pulldown")?.metric,
        )
        assertNull(ExerciseChallengeCatalog.resolve("unknown"))
    }

    @Test
    fun onlyExistingPushupFlowIsLaunchReady() {
        assertTrue(ExerciseChallengeCatalog.default.launchReady)
        ExerciseChallengeCatalog.all
            .filter { it.id != ExerciseId.PUSH_UP }
            .forEach { assertFalse(it.launchReady) }
    }

    @Test
    fun plankKeepsChosenTwentyFiveSecondThresholdInsideThirtySecondRecording() {
        val plank = requireNotNull(ExerciseChallengeCatalog.find(ExerciseId.PLANK))

        assertEquals(25, plank.minimumQualifyingValue)
        assertEquals(30, plank.recordingLimitSeconds)
        assertEquals(ExerciseMetric.HOLD_SECONDS, plank.metric)
    }

    @Test
    fun futureExercisesUseGenericEndpointWithoutChangingPushupConfiguration() {
        val configured = "https://verify.example/v1/pushup-verify"

        assertEquals(configured, verificationUrlForExercise(configured, ExerciseId.PUSH_UP))
        assertEquals(
            "https://verify.example/v1/exercise-verify",
            verificationUrlForExercise(configured, ExerciseId.PLANK),
        )
    }
}
