package com.michalkrsik.internetrestrictor.monitoring

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.PowerManager
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.ContextCompat
import com.michalkrsik.internetrestrictor.appContainer
import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.overlay.BadgeOverlayController
import com.michalkrsik.internetrestrictor.overlay.GateOverlayController
import com.michalkrsik.internetrestrictor.overlay.OverlayCoordinator
import com.michalkrsik.internetrestrictor.ui.challenge.PushupChallengeActivity
import com.michalkrsik.internetrestrictor.ui.challenge.PushupChallengeVisibility
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

class ChromeAccessibilityService : AccessibilityService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository by lazy { appContainer.repository }
    private val eventLog by lazy { appContainer.eventLog }
    private val timeSource by lazy { appContainer.timeSource }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val keyguardManager by lazy { getSystemService(KeyguardManager::class.java) }
    private lateinit var foregroundMonitor: BrowserForegroundMonitor
    private lateinit var gateController: GateOverlayController
    private lateinit var badgeController: BadgeOverlayController
    private lateinit var overlayCoordinator: OverlayCoordinator
    private var renderJob: Job? = null
    private var tickerJob: Job? = null
    private var gateExitConfirmationJob: Job? = null
    private var leaveTimeoutJob: Job? = null
    private var challengeLaunchWatchdogJob: Job? = null
    private var receiverRegistered = false
    private var serviceGeneration = 0L
    private val leaveRearmGuard = LeaveRearmGuard()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val decision = foregroundMonitor.evaluate()
            submitRuntimeDecision(decision) {
                eventLog.record("Screen/keyguard state changed")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceGeneration = nextServiceGeneration.incrementAndGet()
        activeServiceGeneration.set(serviceGeneration)
        foregroundMonitor = BrowserForegroundMonitor(this)
        val windowManager = getSystemService(WindowManager::class.java)
        gateController = GateOverlayController(
            context = this,
            windowManager = windowManager,
            repository = repository,
            scope = serviceScope,
            onPushupChallenge = ::openPushupChallenge,
            onLeaveBrowser = ::leaveBrowser,
            onFocusChanged = ::onGateFocusChanged,
            onAttachFailure = ::onOverlayAttachFailure,
        )
        badgeController = BadgeOverlayController(
            context = this,
            windowManager = windowManager,
            repository = repository,
            onAttachFailure = ::onOverlayAttachFailure,
        )
        overlayCoordinator = OverlayCoordinator(
            gateController = gateController,
            badgeController = badgeController,
            onGateVisibilityChanged = foregroundMonitor::setGateVisible,
        )
        registerScreenReceiver()
        startRendering()
        startConditionalTicker()

        val access = appContainer.accessChecker.read()
        val decision = foregroundMonitor.evaluate()
        serviceScope.launch {
            repository.updateDeviceAccess(
                accessibilityEnabled = true,
                usageAccessGranted = access.usageAccessGranted,
                supportedBrowserInstalled = access.supportedBrowserInstalled,
                advancedProtectionEnabled = access.advancedProtectionEnabled,
            )
            repository.updateServiceRuntime(
                foregroundClass = decision.foregroundClass,
                browserActive = decision.browserActive,
                gateFocused = decision.gateFocused,
                screenInteractive = powerManager.isInteractive,
                keyguardUnlocked = !keyguardManager.isKeyguardLocked,
                serviceConnected = true,
            )
            eventLog.record("Accessibility service connected")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::foregroundMonitor.isInitialized) return
        val decision = foregroundMonitor.evaluate(event)
        if (::overlayCoordinator.isInitialized &&
            overlayCoordinator.isLeaving &&
            !decision.browserActive
        ) {
            completeLeave("Home transition confirmed")
        } else {
            submitRuntimeDecision(decision)
        }
        schedulePendingGateExitConfirmation()
    }

    override fun onInterrupt() {
        eventLog.record("Accessibility service interrupted")
        disconnectRepository()
        removeOverlays()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnectRepository()
        removeOverlays()
        unregisterScreenReceiver()
        return super.onUnbind(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::overlayCoordinator.isInitialized) overlayCoordinator.onConfigurationChanged()
    }

    override fun onDestroy() {
        disconnectRepository()
        removeOverlays()
        unregisterScreenReceiver()
        renderJob?.cancel()
        tickerJob?.cancel()
        gateExitConfirmationJob?.cancel()
        leaveTimeoutJob?.cancel()
        challengeLaunchWatchdogJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRendering() {
        renderJob?.cancel()
        renderJob = serviceScope.launch {
            combine(
                repository.snapshot,
                PushupChallengeVisibility.active,
            ) { snapshot, challengeActive -> snapshot to challengeActive }
                .collect { (snapshot, challengeActive) ->
                overlayCoordinator.render(targetFor(snapshot, challengeActive))
            }
        }
    }

    private fun startConditionalTicker() {
        tickerJob?.cancel()
        tickerJob = serviceScope.launch {
            repository.snapshot
                .map { it.usesFullGate || it.usesBadge }
                .distinctUntilChanged()
                .collectLatest { active ->
                    if (!active) return@collectLatest
                    while (isActive) {
                        delay(TICK_MS)
                        repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
                    }
                }
        }
    }

    private fun onGateFocusChanged(focused: Boolean) {
        if (!::foregroundMonitor.isInitialized) return
        foregroundMonitor.setGateFocus(focused)
        serviceScope.launch {
            repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
            repository.updateServiceRuntime(gateFocused = focused)
        }
    }

    private fun leaveBrowser() {
        if (!::overlayCoordinator.isInitialized || overlayCoordinator.isLeaving) return
        overlayCoordinator.beginLeaving()
        gateExitConfirmationJob?.cancel()
        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            eventLog.record("Leave browser requested; bounded Home transition started")
            leaveTimeoutJob?.cancel()
            leaveTimeoutJob = serviceScope.launch {
                delay(LEAVE_TIMEOUT_MS)
                completeLeave("Home confirmation timed out; gate failed open")
            }
        } else {
            eventLog.record("Leave browser failed: Home action was rejected")
            completeLeave("Home action rejected; gate failed open")
        }
    }

    private fun openPushupChallenge() {
        eventLog.record("Push-up challenge requested")
        PushupChallengeVisibility.prepareLaunch()
        val intent = Intent(this, PushupChallengeActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            foregroundMonitor.lastBrowserPackageName?.let { packageName ->
                putExtra(PushupChallengeActivity.EXTRA_BROWSER_PACKAGE, packageName)
            }
        }
        try {
            // Submit the Activity launch while the user-interactive gate is still attached.
            // Some Android builds silently reject a later background launch after the
            // Accessibility overlay has already been removed.
            startActivity(intent)
            overlayCoordinator.render(OverlayCoordinator.Target.CHALLENGE)
            challengeLaunchWatchdogJob?.cancel()
            challengeLaunchWatchdogJob = serviceScope.launch {
                delay(CHALLENGE_LAUNCH_TIMEOUT_MS)
                if (!PushupChallengeVisibility.activityStarted.value) {
                    eventLog.record("Push-up challenge did not become visible; gate restored")
                    PushupChallengeVisibility.cancelLaunch()
                    renderCurrentOverlayState()
                }
            }
        } catch (error: Exception) {
            eventLog.record("Push-up challenge launch failed: ${error.javaClass.simpleName}")
            PushupChallengeVisibility.cancelLaunch()
            renderCurrentOverlayState()
        }
    }

    private fun onOverlayAttachFailure(error: Throwable) {
        eventLog.record("Overlay attach failed: ${error.javaClass.simpleName}")
        activeServiceGeneration.compareAndSet(serviceGeneration, 0)
        if (::overlayCoordinator.isInitialized) overlayCoordinator.failOpen() else removeOverlays()
        serviceScope.launch {
            repository.updateServiceRuntime(
                foregroundClass = ForegroundClass.OTHER,
                browserActive = false,
                gateFocused = false,
                serviceConnected = false,
            )
        }
    }

    private fun disconnectRepository() {
        eventLog.record("Accessibility service disconnected")
        val disconnectingGeneration = serviceGeneration
        appContainer.applicationScope.launch {
            repository.flushAccounting()
            if (activeServiceGeneration.compareAndSet(disconnectingGeneration, 0)) {
                repository.updateServiceRuntime(
                    foregroundClass = ForegroundClass.OTHER,
                    browserActive = false,
                    gateFocused = false,
                    serviceConnected = false,
                )
            }
        }
    }

    private fun removeOverlays() {
        if (::overlayCoordinator.isInitialized) {
            overlayCoordinator.failOpen()
        } else {
            if (::gateController.isInitialized) gateController.hide()
            if (::badgeController.isInitialized) badgeController.hide()
            if (::foregroundMonitor.isInitialized) foregroundMonitor.setGateVisible(false)
        }
    }

    private fun schedulePendingGateExitConfirmation() {
        if (!foregroundMonitor.hasPendingGateExit) {
            gateExitConfirmationJob?.cancel()
            gateExitConfirmationJob = null
            return
        }
        gateExitConfirmationJob?.cancel()
        gateExitConfirmationJob = serviceScope.launch {
            delay(GateExitStabilizer.DEFAULT_STABLE_FOR_MS)
            val confirmed = foregroundMonitor.confirmPendingGateExit() ?: return@launch
            if (overlayCoordinator.isLeaving) {
                completeLeave("Home transition confirmed")
            } else {
                submitRuntimeDecision(confirmed)
            }
        }
    }

    private fun submitRuntimeDecision(
        decision: ForegroundDecision,
        afterUpdate: (() -> Unit)? = null,
    ) {
        serviceScope.launch {
            repository.updateServiceRuntime(
                foregroundClass = decision.foregroundClass,
                browserActive = decision.browserActive,
                gateFocused = decision.gateFocused,
                screenInteractive = powerManager.isInteractive,
                keyguardUnlocked = !keyguardManager.isKeyguardLocked,
                serviceConnected = true,
            )
            if (leaveRearmGuard.observe(decision.browserActive)) {
                renderCurrentOverlayState()
            }
            afterUpdate?.invoke()
        }
    }

    private fun completeLeave(reason: String) {
        if (!::overlayCoordinator.isInitialized || !overlayCoordinator.isLeaving) return
        leaveTimeoutJob?.cancel()
        leaveTimeoutJob = null
        gateExitConfirmationJob?.cancel()
        gateExitConfirmationJob = null
        leaveRearmGuard.beginSuppression()
        overlayCoordinator.hideWhileLeaving()
        val exited = foregroundMonitor.forceExit()
        eventLog.record(reason)
        serviceScope.launch {
            repository.updateServiceRuntime(
                foregroundClass = exited.foregroundClass,
                browserActive = exited.browserActive,
                gateFocused = exited.gateFocused,
                screenInteractive = powerManager.isInteractive,
                keyguardUnlocked = !keyguardManager.isKeyguardLocked,
                serviceConnected = true,
            )
            overlayCoordinator.finishLeaving()
            delay(POST_LEAVE_RECHECK_MS)
            submitRuntimeDecision(foregroundMonitor.evaluate())
        }
    }

    private fun renderCurrentOverlayState() {
        if (!::overlayCoordinator.isInitialized) return
        val challengeActive = PushupChallengeVisibility.active.value
        overlayCoordinator.render(targetFor(repository.snapshot.value, challengeActive))
    }

    private fun targetFor(
        snapshot: StateSnapshot,
        challengeActive: Boolean,
    ): OverlayCoordinator.Target = when {
        challengeActive -> OverlayCoordinator.Target.CHALLENGE
        leaveRearmGuard.suppressingOverlays -> OverlayCoordinator.Target.HIDDEN
        snapshot.usesFullGate -> OverlayCoordinator.Target.GATE
        snapshot.usesBadge -> OverlayCoordinator.Target.BADGE
        else -> OverlayCoordinator.Target.HIDDEN
    }

    private fun registerScreenReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun unregisterScreenReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(screenReceiver)
        } catch (_: IllegalArgumentException) {
            // Already unregistered.
        }
        receiverRegistered = false
    }

    private companion object {
        const val TICK_MS = 200L
        const val LEAVE_TIMEOUT_MS = 500L
        const val POST_LEAVE_RECHECK_MS = 100L
        const val CHALLENGE_LAUNCH_TIMEOUT_MS = 1_500L
        val nextServiceGeneration = AtomicLong(0)
        val activeServiceGeneration = AtomicLong(0)
    }
}
