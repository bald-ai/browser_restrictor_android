package com.michalkrsik.internetrestrictor.monitoring

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaveRearmGuardTest {
    @Test
    fun sameChromeWindowCannotImmediatelyRelockAfterLeave() {
        val guard = LeaveRearmGuard()
        guard.beginSuppression()

        assertFalse(guard.observe(chromeActive = true))
        assertTrue(guard.suppressingOverlays)
    }

    @Test
    fun chromeRearmsOnlyAfterConfirmedNonChromeObservation() {
        val guard = LeaveRearmGuard()
        guard.beginSuppression()

        assertFalse(guard.observe(chromeActive = false))
        assertTrue(guard.observe(chromeActive = true))
        assertFalse(guard.suppressingOverlays)
    }

    @Test
    fun repeatedNonChromeObservationsKeepSuppressionActive() {
        val guard = LeaveRearmGuard()
        guard.beginSuppression()

        assertFalse(guard.observe(chromeActive = false))
        assertFalse(guard.observe(chromeActive = false))
        assertTrue(guard.suppressingOverlays)
    }
}
