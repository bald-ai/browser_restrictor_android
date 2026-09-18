package com.michalkrsik.internetrestrictor.v1

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PreviewRecordingStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(): Triple<File, File, PreviewRecordingStore> {
        val attempts = temporary.newFolder("attempts")
        val evidence = temporary.newFolder("evidence")
        File(attempts, "preview-1.json").writeText(JSONObject().put("attemptId", "preview-1").put("preview", true).toString())
        return Triple(attempts, evidence, PreviewRecordingStore(attempts, evidence))
    }

    @Test fun claimingBeforeCapturePreventsRecordingAgainAfterRestart() {
        val (attempts, evidence, store) = fixture()
        assertEquals(PreviewRecordingStatus.READY, store.status("preview-1"))
        assertTrue(store.claim("preview-1").getBoolean("captureStarted"))
        val reopened = PreviewRecordingStore(attempts, evidence)
        assertEquals(PreviewRecordingStatus.INTERRUPTED, reopened.status("preview-1"))
        assertThrows(IllegalStateException::class.java) { reopened.claim("preview-1") }
    }

    @Test fun pendingEvidenceCanOnlyBeRetriedWithoutBeingOverwrittenByAnotherRecording() {
        val (attempts, evidence, store) = fixture()
        store.claim("preview-1")
        val pending = File(evidence, "preview-1.json")
        pending.writeText("saved original recording")
        val reopened = PreviewRecordingStore(attempts, evidence)
        assertEquals(PreviewRecordingStatus.WAITING_UPLOAD, reopened.status("preview-1"))
        assertThrows(IllegalStateException::class.java) { reopened.claim("preview-1") }
        assertEquals("saved original recording", pending.readText())
    }

    @Test fun completedRecordingShowsItsExistingDecimalResultAndCannotBeRecordedAgain() {
        val (attempts, evidence, store) = fixture()
        File(attempts, "preview-1-result.json").writeText(JSONObject().put("preview", true).put("measuredUnits", 1.7).toString())
        val reopened = PreviewRecordingStore(attempts, evidence)
        assertEquals(PreviewRecordingStatus.COMPLETE, reopened.status("preview-1"))
        assertEquals(1.7, reopened.result("preview-1")!!.getDouble("measuredUnits"), 0.0)
        assertThrows(IllegalStateException::class.java) { store.claim("preview-1") }
    }

    @Test fun savedResultWinsIfProcessStoppedBeforeDeletingPendingEvidence() {
        val (attempts, evidence, store) = fixture()
        File(attempts, "preview-1-result.json").writeText("{\"preview\":true,\"measuredUnits\":4}")
        File(evidence, "preview-1.json").writeText("original evidence")
        assertEquals(PreviewRecordingStatus.COMPLETE, store.status("preview-1"))
        assertThrows(IllegalStateException::class.java) { store.claim("preview-1") }
    }

    @Test fun unknownOrInvalidIdsCannotClaimAnAttempt() {
        val (_, _, store) = fixture()
        for (id in listOf("missing", "../preview-1", "preview-1/../../outside")) {
            assertEquals(PreviewRecordingStatus.MISSING, store.status(id))
            assertThrows(IllegalStateException::class.java) { store.claim(id) }
        }
        assertNull(store.read(null))
        assertNull(store.result("../preview-1"))
    }
}
