package com.michalkrsik.internetrestrictor.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class PeriodKeys(
    val daily: String,
)

data class WallInterval(val startMs: Long, val endMs: Long) {
    init {
        require(endMs >= startMs)
    }

    val durationMs: Long get() = endMs - startMs
}

object PeriodCalculator {
    fun dailyResetStart(wallMs: Long, zoneId: ZoneId, settings: ConfigurationSettings = ConfigurationSettings()): Long =
        resetAt(LocalDate.parse(keysAt(wallMs, zoneId, settings).daily), resetTime(settings.dayReset), zoneId)

    fun keysAt(wallMs: Long, zoneId: ZoneId, settings: ConfigurationSettings = ConfigurationSettings()): PeriodKeys {
        val current = Instant.ofEpochMilli(wallMs).atZone(zoneId)
        val date = if (wallMs < resetAt(current.toLocalDate(), resetTime(settings.dayReset), zoneId)) {
            current.toLocalDate().minusDays(1)
        } else {
            current.toLocalDate()
        }
        return PeriodKeys(
            daily = date.toString(),
        )
    }

    fun nextDailyReset(wallMs: Long, zoneId: ZoneId, settings: ConfigurationSettings = ConfigurationSettings()): Long {
        val current = Instant.ofEpochMilli(wallMs).atZone(zoneId)
        val todayReset = resetAt(current.toLocalDate(), resetTime(settings.dayReset), zoneId)
        return if (wallMs < todayReset) {
            todayReset
        } else {
            resetAt(current.toLocalDate().plusDays(1), resetTime(settings.dayReset), zoneId)
        }
    }

    fun splitAtDailyResets(startMs: Long, endMs: Long, zoneId: ZoneId, settings: ConfigurationSettings = ConfigurationSettings()): List<WallInterval> {
        if (endMs <= startMs) return emptyList()
        val result = mutableListOf<WallInterval>()
        var cursor = startMs
        while (cursor < endMs) {
            val boundary = nextDailyReset(cursor, zoneId, settings)
            val segmentEnd = minOf(endMs, boundary)
            result += WallInterval(cursor, segmentEnd)
            cursor = segmentEnd
        }
        return result
    }

    fun budgetKey(wallMs: Long, zoneId: ZoneId, settings: ConfigurationSettings = ConfigurationSettings()): String =
        keysAt(wallMs, zoneId, settings).daily

    fun localDate(wallMs: Long, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(wallMs).atZone(zoneId).toLocalDate()

    /** Repeated local times reset once, at the first occurrence. A missing local
     * time resets at the first valid instant after the daylight-saving gap. */
    private fun resetAt(date: LocalDate, time: LocalTime, zoneId: ZoneId): Long {
        val local = date.atTime(time)
        val offsets = zoneId.rules.getValidOffsets(local)
        return if (offsets.isEmpty()) zoneId.rules.getTransition(local)!!.instant.toEpochMilli()
        else local.toInstant(offsets.first()).toEpochMilli()
    }
}
