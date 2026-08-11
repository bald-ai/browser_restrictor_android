package com.michalkrsik.internetrestrictor.data

import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.PendingWait
import com.michalkrsik.internetrestrictor.domain.PersistedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RestrictorRepositoryPolicyTest {
    @Test
    fun disconnectPreservesLastConfirmedBrowserForeground() {
        assertEquals(
            ForegroundClass.BROWSER,
            persistedForegroundAfterServiceUpdate(
                previous = ForegroundClass.BROWSER,
                observed = ForegroundClass.OTHER,
                serviceConnected = false,
            ),
        )
    }

    @Test
    fun connectedObservationReplacesPersistedForeground() {
        assertEquals(
            ForegroundClass.OTHER,
            persistedForegroundAfterServiceUpdate(
                previous = ForegroundClass.BROWSER,
                observed = ForegroundClass.OTHER,
                serviceConnected = true,
            ),
        )
    }

    @Test
    fun leavingBrowserAbortsPendingWait() {
        val state = PersistedState(
            pendingWait = PendingWait(
                id = "wait",
                selectedDurationMs = 10_000,
                requiredFocusMs = 5_000,
                accumulatedFocusMs = 2_000,
                createdAtWallMs = 1_000,
                updatedAtWallMs = 3_000,
            ),
        )

        val updated = abortPendingWaitAfterServiceUpdate(state, browserActive = false)

        assertNull(updated.pendingWait)
    }

    @Test
    fun pendingWaitRemainsWhileBrowserFlowIsActive() {
        val state = PersistedState(
            pendingWait = PendingWait("wait", 10_000, 5_000, 2_000, 1_000, 3_000),
        )

        val updated = abortPendingWaitAfterServiceUpdate(state, browserActive = true)

        assertNotNull(updated.pendingWait)
    }
}
