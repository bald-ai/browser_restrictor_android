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
    fun keysAt(wallMs: Long, zoneId: ZoneId): PeriodKeys {
        val current = Instant.ofEpochMilli(wallMs).atZone(zoneId)
        val resetTime = LocalTime.of(DAILY_RESET_HOUR, 0)
        val date = if (current.toLocalTime() < resetTime) {
            current.toLocalDate().minusDays(1)
        } else {
            current.toLocalDate()
        }
        return PeriodKeys(
            daily = date.toString(),
        )
    }

    fun nextDailyReset(wallMs: Long, zoneId: ZoneId): Long {
        val current = Instant.ofEpochMilli(wallMs).atZone(zoneId)
        val todayReset = current.toLocalDate()
            .atTime(LocalTime.of(DAILY_RESET_HOUR, 0))
            .atZone(zoneId)
        val next = if (current.toInstant() < todayReset.toInstant()) {
            todayReset
        } else {
            current.toLocalDate().plusDays(1)
                .atTime(LocalTime.of(DAILY_RESET_HOUR, 0))
                .atZone(zoneId)
        }
        return next.toInstant().toEpochMilli()
    }

    fun eveningBudgetReset(wallMs: Long, zoneId: ZoneId): Long {
        val date = LocalDate.parse(keysAt(wallMs, zoneId).daily)
        return date.atTime(LocalTime.of(EVENING_BUDGET_RESET_HOUR, 0))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()
    }

    fun splitAtBudgetBoundaries(startMs: Long, endMs: Long, zoneId: ZoneId): List<WallInterval> {
        if (endMs <= startMs) return emptyList()
        val result = mutableListOf<WallInterval>()
        var cursor = startMs
        while (cursor < endMs) {
            val eveningReset = eveningBudgetReset(cursor, zoneId)
            val dailyReset = nextDailyReset(cursor, zoneId)
            val boundary = if (cursor < eveningReset) minOf(eveningReset, dailyReset) else dailyReset
            val segmentEnd = minOf(endMs, boundary)
            result += WallInterval(cursor, segmentEnd)
            cursor = segmentEnd
        }
        return result
    }

    fun splitAtDailyResets(startMs: Long, endMs: Long, zoneId: ZoneId): List<WallInterval> {
        if (endMs <= startMs) return emptyList()
        val result = mutableListOf<WallInterval>()
        var cursor = startMs
        while (cursor < endMs) {
            val boundary = nextDailyReset(cursor, zoneId)
            val segmentEnd = minOf(endMs, boundary)
            result += WallInterval(cursor, segmentEnd)
            cursor = segmentEnd
        }
        return result
    }

    fun localDate(wallMs: Long, zoneId: ZoneId): LocalDate =
        Instant.ofEpochMilli(wallMs).atZone(zoneId).toLocalDate()
}
