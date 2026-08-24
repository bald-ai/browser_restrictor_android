package com.michalkrsik.internetrestrictor.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

object MonthlyPushupCalculator {
    val zoneId: ZoneId = ZoneId.of("Europe/Prague")

    fun cycleStartDate(wallMs: Long): LocalDate {
        val date = Instant.ofEpochMilli(wallMs).atZone(zoneId).toLocalDate()
        return if (date.dayOfMonth >= CYCLE_START_DAY) {
            date.withDayOfMonth(CYCLE_START_DAY)
        } else {
            date.minusMonths(1).withDayOfMonth(CYCLE_START_DAY)
        }
    }

    fun cycleStartKey(wallMs: Long): String = cycleStartDate(wallMs).toString()

    fun nextCycleStart(startDate: String): String =
        LocalDate.parse(startDate).plusMonths(1).withDayOfMonth(CYCLE_START_DAY).toString()

    fun cycleEndWallMs(startDate: String): Long = LocalDate.parse(nextCycleStart(startDate))
        .atStartOfDay(zoneId)
        .toInstant()
        .toEpochMilli()

    fun ensureCurrentCycle(
        cycles: List<MonthlyPushupCycle>,
        nowWallMs: Long,
    ): List<MonthlyPushupCycle> {
        val currentKey = cycleStartKey(nowWallMs)
        if (cycles.any { it.cycleStartDate == currentKey }) return cycles.sortedBy { it.cycleStartDate }

        val result = cycles.associateBy { it.cycleStartDate }.toMutableMap()
        val latestPastKey = result.keys.filter { it < currentKey }.maxOrNull()
        if (latestPastKey == null) {
            result[currentKey] = MonthlyPushupCycle(currentKey)
        } else {
            var nextKey = nextCycleStart(latestPastKey)
            while (nextKey <= currentKey) {
                result.putIfAbsent(nextKey, MonthlyPushupCycle(nextKey))
                nextKey = nextCycleStart(nextKey)
            }
        }
        return result.values.sortedBy { it.cycleStartDate }
    }

    fun addPushups(
        cycles: List<MonthlyPushupCycle>,
        repetitions: Long,
        nowWallMs: Long,
    ): List<MonthlyPushupCycle> {
        require(repetitions > 0)
        val currentKey = cycleStartKey(nowWallMs)
        return ensureCurrentCycle(cycles, nowWallMs).map { cycle ->
            if (cycle.cycleStartDate == currentKey) {
                cycle.copy(totalPushups = Math.addExact(cycle.totalPushups, repetitions))
            } else {
                cycle
            }
        }
    }

    private const val CYCLE_START_DAY = 9
}
