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
import com.michalkrsik.internetrestrictor.overlay.BadgeOverlayController
import com.michalkrsik.internetrestrictor.overlay.GateOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
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
    private lateinit var foregroundMonitor: ChromeForegroundMonitor
    private lateinit var gateController: GateOverlayController
    private lateinit var badgeController: BadgeOverlayController
    private var renderJob: Job? = null
    private var tickerJob: Job? = null
    private var receiverRegistered = false
    private var serviceGeneration = 0L

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val decision = foregroundMonitor.evaluate()
            serviceScope.launch {
                repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
                repository.updateServiceRuntime(
                    foregroundClass = decision.foregroundClass,
                    chromeActive = decision.chromeActive,
                    gateFocused = decision.gateFocused,
                    screenInteractive = powerManager.isInteractive,
                    keyguardUnlocked = !keyguardManager.isKeyguardLocked,
                    serviceConnected = true,
                )
                eventLog.record("Screen/keyguard state changed")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceGeneration = nextServiceGeneration.incrementAndGet()
        activeServiceGeneration.set(serviceGeneration)
        foregroundMonitor = ChromeForegroundMonitor(this)
        val windowManager = getSystemService(WindowManager::class.java)
        gateController = GateOverlayController(
            context = this,
            windowManager = windowManager,
            repository = repository,
            scope = serviceScope,
            onLeaveChrome = ::leaveChrome,
            onFocusChanged = ::onGateFocusChanged,
            onAttachFailure = ::onOverlayAttachFailure,
        )
        badgeController = BadgeOverlayController(
            context = this,
            windowManager = windowManager,
            repository = repository,
            onAttachFailure = ::onOverlayAttachFailure,
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
                chromeInstalled = access.chromeInstalled,
                advancedProtectionEnabled = access.advancedProtectionEnabled,
            )
            repository.updateServiceRuntime(
                foregroundClass = decision.foregroundClass,
                chromeActive = decision.chromeActive,
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
        serviceScope.launch {
            repository.tick(timeSource.elapsedRealtimeMs(), timeSource.wallTimeMs())
            repository.updateServiceRuntime(
                foregroundClass = decision.foregroundClass,
                chromeActive = decision.chromeActive,
                gateFocused = decision.gateFocused,
                screenInteractive = powerManager.isInteractive,
                keyguardUnlocked = !keyguardManager.isKeyguardLocked,
                serviceConnected = true,
            )
        }
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
        if (::gateController.isInitialized) gateController.recreateForConfigurationChange()
        if (::badgeController.isInitialized) badgeController.recreateForConfigurationChange()
    }

    override fun onDestroy() {
        disconnectRepository()
        removeOverlays()
        unregisterScreenReceiver()
        renderJob?.cancel()
        tickerJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startRendering() {
        renderJob?.cancel()
        renderJob = serviceScope.launch {
            repository.snapshot.collect { snapshot ->
                when {
                    snapshot.usesFullGate -> {
                        badgeController.hide()
                        foregroundMonitor.setGateVisible(true)
                        gateController.show()
                    }
                    snapshot.usesBadge -> {
                        foregroundMonitor.setGateVisible(false)
                        gateController.hide()
                        badgeController.show()
                    }
                    else -> {
                        foregroundMonitor.setGateVisible(false)
                        gateController.hide()
                        badgeController.hide()
                    }
                }
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

    private fun leaveChrome() {
        if (performGlobalAction(GLOBAL_ACTION_HOME)) {
            // Keep the gate attached during Android's Home transition. The foreground
            // monitor owns the Chrome latch and will release it only after a definitive
            // non-Chrome window arrives. Removing the overlay here exposes Chrome for a
            // few frames and causes its transition events to show the gate again.
            eventLog.record("Leave Chrome requested; waiting for confirmed Home transition")
        } else {
            eventLog.record("Leave Chrome failed: Home action was rejected")
        }
    }

    private fun onOverlayAttachFailure(error: Throwable) {
        eventLog.record("Overlay attach failed: ${error.javaClass.simpleName}")
        activeServiceGeneration.compareAndSet(serviceGeneration, 0)
        removeOverlays()
        serviceScope.launch {
            repository.updateServiceRuntime(
                foregroundClass = ForegroundClass.OTHER,
                chromeActive = false,
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
                    chromeActive = false,
                    gateFocused = false,
                    serviceConnected = false,
                )
            }
        }
    }

    private fun removeOverlays() {
        if (::gateController.isInitialized) gateController.hide()
        if (::badgeController.isInitialized) badgeController.hide()
        if (::foregroundMonitor.isInitialized) foregroundMonitor.setGateVisible(false)
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
        val nextServiceGeneration = AtomicLong(0)
        val activeServiceGeneration = AtomicLong(0)
    }
}
