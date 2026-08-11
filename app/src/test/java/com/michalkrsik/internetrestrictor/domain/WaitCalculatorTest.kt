package com.michalkrsik.internetrestrictor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitCalculatorTest {
    @Test
    fun everySupportedSessionUsesFixedTwelveSecondWait() {
        assertEquals(12_000, WaitCalculator.requiredFocusMs(10))
        assertEquals(12_000, WaitCalculator.requiredFocusMs(30))
        assertEquals(12_000, WaitCalculator.requiredFocusMs(60))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedSessionIsRejected() {
        WaitCalculator.requiredFocusMs(20)
    }

    @Test
    fun countdownRoundsUp() {
        assertEquals(2, WaitCalculator.displaySeconds(1_001))
        assertEquals(1, WaitCalculator.displaySeconds(1))
        assertEquals(0, WaitCalculator.displaySeconds(0))
    }
}
