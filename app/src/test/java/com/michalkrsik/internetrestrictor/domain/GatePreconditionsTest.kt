package com.michalkrsik.internetrestrictor.domain

import org.junit.Test
import org.junit.Assert.*
import java.time.ZoneId

class GatePreconditionsTest {
    private val now = 1_790_250_000_000L
    private val zone = ZoneId.of("UTC")
    private val engine = RestrictionEngine()
    private val ready = RuntimeSignals(foregroundClass = ForegroundClass.BROWSER,
        activeTarget = ProtectedTarget.BROWSER_POOL, gateFocused = true, serviceConnected = true,
        accessibilityEnabled = true, storageReady = true, gateSessionId = "gate")
    private fun state() = (engine.earnPushupBudget(PersistedState(), now, zone) as CommandResult.Applied).state

    @Test fun exerciseAwardsRejectInvalidIdentifiersFullHistoryAndUnsafeBalanceIncrements() {
        val base = state()
        val full = base.copy(dailyCounter = base.dailyCounter.copy(creditedExerciseVerificationIds = List(256) { "receipt-$it" }))
        val capped = base.copy(dailyCounter = base.dailyCounter.copy(earnedBudgetRounds = MAX_SAFE_BUDGET_ROUNDS, spendableBudgetRounds = MAX_SAFE_BUDGET_ROUNDS))
        val cases = listOf(base to "", base to "a".repeat(129), full to "new", capped to "new")
        for ((input, id) in cases) {
            val result = engine.recordVerifiedExercise(input, id, ExerciseId.PUSH_UP, 5, now, zone)
            assertTrue(result.toString(), result is CommandResult.Rejected)
            assertEquals(input.dailyCounter, (result as CommandResult.Rejected).state.dailyCounter)
        }
    }

    @Test fun latchedExhaustionRequiresTheOriginalBrowserTargetAndConnectedService() {
        val input = state()
        val latched = ready.copy(outOfTimeLatchToken = "latch")
        assertEquals(GateMode.OUT_OF_TIME_LATCHED, engine.snapshot(input, latched, now).canonicalMode)
        assertEquals(GateMode.PAUSED, engine.snapshot(input, ready, now).canonicalMode)
        assertEquals(GateMode.YOUTUBE_LOCKED, engine.snapshot(input, latched.copy(activeTarget = ProtectedTarget.YOUTUBE), now).canonicalMode)
        assertEquals(GateMode.SERVICE_UNAVAILABLE, engine.snapshot(input, latched.copy(serviceConnected = false), now).canonicalMode)
    }

    @Test fun completedWaitCannotUnlockAfterAnyFocusOrAvailabilityPreconditionChanges() {
        val base = state()
        val waiting = (engine.startWait(base, ready, base.stateRevision, 3, now, zone) as CommandResult.Applied).state
        val complete = waiting.copy(pendingWait = waiting.pendingWait!!.copy(accumulatedFocusMs = waiting.pendingWait!!.requiredFocusMs))
        for (changed in listOf(ready.copy(gateFocused = false), ready.copy(gateSessionId = null), ready.copy(gateSessionId = "replacement"),
            ready.copy(screenInteractive = false), ready.copy(keyguardUnlocked = false), ready.copy(serviceConnected = false),
            ready.copy(accessibilityEnabled = false), ready.copy(storageReady = false), ready.copy(activeTarget = null))) {
            val advance = engine.advanceWait(waiting, changed, 100, now + 100, zone, "gate")
            assertTrue(changed.toString(), advance is CommandResult.Rejected)
            val result = engine.completeNormalWait(complete, changed, now, zone, complete.pendingWait!!.id, "gate")
            assertTrue(changed.toString(), result is CommandResult.Rejected)
            assertNull((result as CommandResult.Rejected).state.activeUnlock)
        }
        val deniedSkip = engine.emergencySkip(complete, ready.copy(gateFocused = false), complete.stateRevision, complete.pendingWait!!.id, now, zone)
        assertTrue(deniedSkip is CommandResult.Rejected)
        val completed = engine.completeNormalWait(complete, ready, now, zone, complete.pendingWait!!.id, "gate")
        assertTrue(completed is CommandResult.Applied)
        assertEquals(UnlockSource.NORMAL, (completed as CommandResult.Applied).state.activeUnlock!!.source)
    }

    @Test fun healthReportsWriteFailuresAndAdvancedProtectionWithoutOverridingGrantedAccess() {
        val input = state()
        val expectations = listOf(
            ready.copy(storageWriteError = true) to ProtectionHealth.STORAGE_ERROR,
            ready.copy(storageError = true) to ProtectionHealth.STORAGE_ERROR,
            ready.copy(advancedProtectionEnabled = true, accessibilityEnabled = true) to ProtectionHealth.READY_LIMITED_RECOVERY,
            ready.copy(advancedProtectionEnabled = true, accessibilityEnabled = false) to ProtectionHealth.ADVANCED_PROTECTION_BLOCKED,
            ready.copy(usageAccessGranted = true, usageRecoveryOperational = true) to ProtectionHealth.READY,
        )
        for ((runtime, health) in expectations) assertEquals(health, engine.snapshot(input, runtime, now).enforcementHealth)
    }
}
