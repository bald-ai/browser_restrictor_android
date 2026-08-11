package com.michalkrsik.internetrestrictor.domain

import java.time.Instant
import java.time.LocalDate
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
    fun keysAt(wallMs: Long, zoneId: ZoneId): PeriodKeys {
        val date = Instant.ofEpochMilli(wallMs).atZone(zoneId).toLocalDate()
        return PeriodKeys(
            daily = date.toString(),
        )
    }

    fun nextLocalMidnight(wallMs: Long, zoneId: ZoneId): Long {
        val current = Instant.ofEpochMilli(wallMs).atZone(zoneId)
        return current.toLocalDate().plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    }

    fun splitAtLocalMidnights(startMs: Long, endMs: Long, zoneId: ZoneId): List<WallInterval> {
        if (endMs <= startMs) return emptyList()
        val result = mutableListOf<WallInterval>()
        var cursor = startMs
        while (cursor < endMs) {
            val boundary = nextLocalMidnight(cursor, zoneId)
            val segmentEnd = minOf(endMs, boundary)
            result += WallInterval(cursor, segmentEnd)
            cursor = segmentEnd
        }
        return result
    }

    fun localDate(wallMs: Long, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(wallMs).atZone(zoneId).toLocalDate()
}
