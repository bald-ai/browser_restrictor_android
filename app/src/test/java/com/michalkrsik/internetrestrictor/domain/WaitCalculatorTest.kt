package com.michalkrsik.internetrestrictor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class WaitCalculatorTest {
    @Test
    fun countdownRoundsUp() {
        assertEquals(2, WaitCalculator.displaySeconds(1_001))
        assertEquals(1, WaitCalculator.displaySeconds(1))
        assertEquals(0, WaitCalculator.displaySeconds(0))
    }
}
