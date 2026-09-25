package com.michalkrsik.internetrestrictor.ui

import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.gate.*
import org.junit.Test
import org.junit.Assert.*
import java.time.ZoneId

class GateCopyTest {
    @Test fun labelsDistinguishWaitingExhaustionYoutubeAndUnavailableModes() {
        val engine = RestrictionEngine()
        val now = 1_790_250_000_000L
        val snapshot = engine.snapshot(engine.normalize(PersistedState(), now, ZoneId.of("UTC")), RuntimeSignals(), now)
        for (mode in listOf(GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED)) {
            assertEquals("BUDGET USED", eyebrow(mode))
            assertEquals("Browser time is used up.", headline(snapshot.copy(canonicalMode = mode)))
            assertEquals("Budget used. Try again after reset.", supportingCopy(snapshot.copy(canonicalMode = mode)))
        }
        val waiting = snapshot.copy(canonicalMode = GateMode.WAITING, waitSnapshot = null)
        assertEquals("ALMOST THERE", eyebrow(GateMode.WAITING))
        assertEquals("Hang on a moment.", headline(waiting))
        assertEquals("Your 0 min session is opening soon.", supportingCopy(waiting))
        val youtube = snapshot.copy(canonicalMode = GateMode.YOUTUBE_LOCKED)
        assertEquals("MORNING GATE", eyebrow(youtube.canonicalMode))
        assertEquals("Exercise before YouTube.", headline(youtube))
        assertEquals("Any combination of 4 credits unlocks it until 04:00.", supportingCopy(youtube))
        for (mode in listOf(GateMode.STORAGE_ERROR, GateMode.SERVICE_UNAVAILABLE, GateMode.BROWSER_BACKGROUND)) {
            assertEquals("BROWSER", eyebrow(mode))
            assertEquals("Browser", headline(snapshot.copy(canonicalMode = mode)))
            assertEquals("", supportingCopy(snapshot.copy(canonicalMode = mode)))
        }
    }
}
