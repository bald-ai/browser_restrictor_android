package com.michalkrsik.internetrestrictor.domain

import kotlin.math.ceil

object WaitCalculator {
    val sessionMinutes = listOf(10L, 30L, 60L)

    fun requiredFocusMs(requestedMinutes: Long): Long {
        require(requestedMinutes in sessionMinutes)
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
