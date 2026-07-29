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
    fun disconnectPreservesLastConfirmedChromeForeground() {
        assertEquals(
            ForegroundClass.CHROME,
            persistedForegroundAfterServiceUpdate(
                previous = ForegroundClass.CHROME,
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
                previous = ForegroundClass.CHROME,
                observed = ForegroundClass.OTHER,
                serviceConnected = true,
            ),
        )
    }

    @Test
    fun leavingChromeAbortsPendingWait() {
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

        val updated = abortPendingWaitAfterServiceUpdate(state, chromeActive = false)

        assertNull(updated.pendingWait)
    }

    @Test
    fun pendingWaitRemainsWhileChromeFlowIsActive() {
        val state = PersistedState(
            pendingWait = PendingWait("wait", 10_000, 5_000, 2_000, 1_000, 3_000),
        )

        val updated = abortPendingWaitAfterServiceUpdate(state, chromeActive = true)

        assertNotNull(updated.pendingWait)
    }
}
