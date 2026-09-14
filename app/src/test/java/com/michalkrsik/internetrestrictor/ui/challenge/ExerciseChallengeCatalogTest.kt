package com.michalkrsik.internetrestrictor.ui.challenge

import com.michalkrsik.internetrestrictor.domain.ExerciseId
import com.michalkrsik.internetrestrictor.domain.ExerciseMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExerciseChallengeCatalogTest {
    @Test
    fun retryAfterSupportsSecondsDatesAndInvalidHeaders() {
        org.junit.Assert.assertEquals(5_000L, retryAfterDelayMs("5"))
        org.junit.Assert.assertEquals(5_000L, retryAfterDelayMs("invalid"))
        org.junit.Assert.assertEquals(10_000L, retryAfterDelayMs("Thu, 01 Jan 1970 00:00:10 GMT", 0L))
    }

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
    fun creditedExercisesAreLaunchReadyWithoutAPicker() {
        assertTrue(ExerciseChallengeCatalog.find(ExerciseId.PUSH_UP)!!.launchReady)
        assertTrue(ExerciseChallengeCatalog.find(ExerciseId.PLANK)!!.launchReady)
        assertTrue(ExerciseChallengeCatalog.find(ExerciseId.OUTDOOR_LAT_PULLDOWN)!!.launchReady)
        assertFalse(ExerciseChallengeCatalog.find(ExerciseId.SIT_UP)!!.launchReady)
    }

    @Test
    fun plankAndMachineUseTheAgreedSetSizesInsideThirtySecondRecording() {
        val plank = requireNotNull(ExerciseChallengeCatalog.find(ExerciseId.PLANK))
        val machine = requireNotNull(ExerciseChallengeCatalog.find(ExerciseId.OUTDOOR_LAT_PULLDOWN))

        assertEquals(15, plank.unitsPerSet)
        assertEquals(30, plank.recordingLimitSeconds)
        assertEquals(10, machine.unitsPerSet)
        assertEquals(30, machine.recordingLimitSeconds)
    }

    @Test
    fun configuredPushupUrlIsRewrittenToTheGenericClassifierEndpoint() {
        val configured = "https://verify.example/v1/pushup-verify"

        assertEquals("https://verify.example/v1/exercise-verify", verificationUrl(configured))
        assertEquals(
            "https://verify.example/v1/exercise-verify",
            verificationUrl("https://verify.example/v1/exercise-verify"),
        )
    }

    @Test
    fun classifierResultDoesNotRequireAPickedExercise() {
        val plank = parseExerciseVerificationResult(
            """{"exercise_id":"plank","metric":"hold_seconds","value":20,"reason":"Hold"}""",
        )
        assertEquals(ExerciseId.PLANK, plank.exerciseId)
        assertEquals(ExerciseMetric.HOLD_SECONDS, plank.metric)
        assertEquals(20, plank.value)

        val unsupported = parseExerciseVerificationResult(
            """{"exercise_id":"unsupported","metric":"none","value":0,"reason":"Walking"}""",
        )
        assertEquals(ExerciseId.UNSUPPORTED, unsupported.exerciseId)
        assertEquals(0, unsupported.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun classifierResultRejectsAMetricThatDoesNotMatchTheExercise() {
        parseExerciseVerificationResult(
            """{"exercise_id":"plank","metric":"repetitions","value":18,"reason":"Hold"}""",
        )
    }

}
