package com.michalkrsik.internetrestrictor.domain

import kotlin.math.ceil

data class SessionOption(val minutes: Long, val enabled: Boolean)

object SessionOptions {
    /**
     * Presets longer than the remaining budget are disabled. When even the shortest
     * preset does not fit, it is replaced by the whole minutes still available.
     */
    fun options(presets: List<Long>, budgets: List<BudgetSnapshot>): List<SessionOption> {
        val available = availableWholeMinutes(budgets)
            ?: return presets.map { SessionOption(it, enabled = true) }
        val shortest = presets.minOrNull() ?: return emptyList()
        if (available in MIN_SESSION_MINUTES until shortest) {
            return presets.map { if (it == shortest) SessionOption(available, true) else SessionOption(it, false) }
        }
        return presets.map { SessionOption(it, enabled = it <= available) }
    }

    fun allows(presets: List<Long>, budgets: List<BudgetSnapshot>, minutes: Long): Boolean =
        options(presets, budgets).any { it.enabled && it.minutes == minutes }

    /** Budget is charged in whole minutes, so only completed minutes are offered. */
    fun availableWholeMinutes(budgets: List<BudgetSnapshot>): Long? = budgets
        .filter { it.enabled }
        .mapNotNull { it.remainingMs }
        .minOrNull()
        ?.let { it.coerceAtLeast(0) / MINUTE_MS }
}

/** What the badge counts down: the unlock window, or the budget when that runs out first. */
data class UnlockCountdown(val remainingMs: Long, val totalMs: Long) {
    /** The minute in progress still counts, matching the whole-minute budget ledger. */
    val displayMinutes: Long get() = ceil(remainingMs / MINUTE_MS.toDouble()).toLong()
    val progress: Float get() = (remainingMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
}

fun StateSnapshot.unlockCountdown(): UnlockCountdown? {
    val unlock = activeUnlock ?: return null
    val windowMs = (unlock.expiresAtWallMs - wallNowMs).coerceAtLeast(0)
    val budgetMs = if (unlock.source == UnlockSource.NORMAL) {
        budgetSnapshots.filter { it.enabled }.mapNotNull { it.remainingMs }.minOrNull()
    } else null
    return UnlockCountdown(
        remainingMs = minOf(windowMs, budgetMs?.coerceAtLeast(0) ?: Long.MAX_VALUE),
        totalMs = (unlock.expiresAtWallMs - unlock.grantedAtWallMs).coerceAtLeast(1),
    )
}
