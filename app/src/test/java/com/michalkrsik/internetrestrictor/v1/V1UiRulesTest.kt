package com.michalkrsik.internetrestrictor.v1

import com.michalkrsik.internetrestrictor.domain.ConfigurationSettings
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class V1UiRulesTest {
    private val zone = ZoneId.of("Europe/Prague")
    private fun at(hour: Int) = ZonedDateTime.of(2026, 9, 16, hour, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun browsingResetShowsTheNextRealExpiry() {
        assertEquals("04:00", nextDailyResetLabel(at(2), ConfigurationSettings(), zone))
        assertEquals("04:00", nextDailyResetLabel(at(12), ConfigurationSettings(), zone))
        assertEquals("04:00", nextDailyResetLabel(at(21), ConfigurationSettings(), zone))
    }

    @Test fun customResetHoursAreRespected() {
        assertEquals("05:00", nextDailyResetLabel(at(2), ConfigurationSettings(dayReset = "05:00"), zone))
        assertEquals("05:00", nextDailyResetLabel(at(4), ConfigurationSettings(dayReset = "05:00"), zone))
    }

    @Test fun decimalCorrectionsPreserveDurationAndDistance() {
        assertEquals(1.7, parseCorrectionValue("1.7", "seconds")!!, 0.0)
        assertEquals(1.7, parseCorrectionValue("1,7", "meters")!!, 0.0)
        assertNull(parseCorrectionValue("1.7", "repetitions"))
        listOf("NaN", "Infinity", "-1", "100001", "").forEach { assertNull(parseCorrectionValue(it, "seconds")) }
    }
}
