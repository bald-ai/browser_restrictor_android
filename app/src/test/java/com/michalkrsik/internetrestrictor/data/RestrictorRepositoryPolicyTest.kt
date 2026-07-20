package com.michalkrsik.internetrestrictor.data

import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import org.junit.Assert.assertEquals
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
}
