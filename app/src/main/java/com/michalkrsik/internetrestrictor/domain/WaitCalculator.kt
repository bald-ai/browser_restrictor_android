package com.michalkrsik.internetrestrictor.domain

import kotlin.math.ceil

object WaitCalculator {
    fun requiredFocusMs(requestedMinutes: Long): Long {
        require(requestedMinutes in MIN_SESSION_MINUTES..MAX_SESSION_MINUTES)
        return FIXED_WAIT_MS
    }

    fun displaySeconds(remainingMs: Long): Long =
        ceil(remainingMs.coerceAtLeast(0) / 1_000.0).toLong()

    fun formatWait(ms: Long): String = if (ms < MINUTE_MS) {
        "${displaySeconds(ms)} sec"
    } else {
        val minutes = ms / MINUTE_MS.toDouble()
        "%.1f min".format(minutes)
    }
}
