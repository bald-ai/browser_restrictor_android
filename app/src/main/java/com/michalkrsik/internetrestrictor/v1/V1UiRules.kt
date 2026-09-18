package com.michalkrsik.internetrestrictor.v1

import com.michalkrsik.internetrestrictor.domain.ConfigurationSettings
import com.michalkrsik.internetrestrictor.domain.PeriodCalculator
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun nextDailyResetLabel(nowMs: Long, settings: ConfigurationSettings, zone: ZoneId = ZoneId.systemDefault()): String {
    val next = PeriodCalculator.nextDailyReset(nowMs, zone, settings)
    return DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(next).atZone(zone))
}

internal fun parseCorrectionValue(text: String, metric: String): Double? {
    val value = text.trim().replace(',', '.').toDoubleOrNull() ?: return null
    if (!value.isFinite() || value !in 0.0..100_000.0) return null
    if (metric == "repetitions" && value % 1.0 != 0.0) return null
    return value
}
