package com.michalkrsik.internetrestrictor.domain

import kotlin.math.ceil

object WaitCalculator {
    fun displaySeconds(remainingMs: Long): Long =
        ceil(remainingMs.coerceAtLeast(0) / 1_000.0).toLong()
}
