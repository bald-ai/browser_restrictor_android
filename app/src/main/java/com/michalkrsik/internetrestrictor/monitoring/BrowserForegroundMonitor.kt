package com.michalkrsik.internetrestrictor.monitoring

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.ProtectedTarget
import com.michalkrsik.internetrestrictor.domain.isSupportedBrowserPackage
import com.michalkrsik.internetrestrictor.domain.protectedTargetForPackage

data class ForegroundDecision(
    val foregroundClass: ForegroundClass,
    val activeTarget: ProtectedTarget?,
    val gateFocused: Boolean,
) {
    val targetActive: Boolean get() = activeTarget != null
}

class BrowserForegroundMonitor(
    private val service: AccessibilityService,
) {
    private var latchedTarget: ProtectedTarget? = null
    private var gateVisible = false
    private var gateHasFocus = false
    private var lastDecision = ForegroundDecision(ForegroundClass.OTHER, null, false)
    private val exitStabilizer = GateExitStabilizer()

    var lastProtectedPackageName: String? = null
        private set

    val hasPendingGateExit: Boolean get() = exitStabilizer.hasPendingCandidate

    fun setGateVisible(visible: Boolean) {
        gateVisible = visible
        if (!visible) {
            gateHasFocus = false
            exitStabilizer.reset()
        }
    }

    fun setGateFocus(focused: Boolean) {
        gateHasFocus = focused
    }

    fun evaluate(
        event: AccessibilityEvent? = null,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): ForegroundDecision {
        val windows = service.windows.orEmpty()
        val focused = windows.firstOrNull { it.isFocused }
        val decision = when {
            gateVisible && latchedTarget != null -> {
                val activePackage = activeApplicationPackage(windows)
                val switchedTarget = protectedTargetForPackage(activePackage)
                    ?.takeIf { it != latchedTarget }
                if (switchedTarget != null) {
                    val owner = packageClassification(activePackage)
                    ForegroundDecision(owner.foregroundClass, switchedTarget, false)
                } else {
                    val exitCandidate = gateExitCandidatePackage(event, focused, windows)
                    if (exitStabilizer.observe(exitCandidate, nowElapsedMs)) {
                        ForegroundDecision(ForegroundClass.OTHER, null, false)
                    } else {
                        ForegroundDecision(
                            ForegroundClass.RESTRICTOR,
                            latchedTarget,
                            gateHasFocus || focused?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                        )
                    }
                }
            }
            focused?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD -> classifyImeContext(windows)
            focused?.type == AccessibilityWindowInfo.TYPE_APPLICATION -> classifyApplication(focused, event)
            focused != null -> ForegroundDecision(ForegroundClass.OTHER, null, false)
            else -> {
                val activeApp = windows.firstOrNull {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.isFocused
                }
                activeApp?.let { classifyApplication(it, event) } ?: classifyEventFallback(event)
            }
        }

        when {
            decision.activeTarget != null -> {
                latchedTarget = decision.activeTarget
            }
            decision.foregroundClass == ForegroundClass.RESTRICTOR && gateVisible && decision.targetActive -> Unit
            else -> latchedTarget = null
        }
        lastDecision = decision
        return decision
    }

    fun confirmPendingGateExit(
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): ForegroundDecision? {
        if (!gateVisible || latchedTarget == null || !exitStabilizer.hasPendingCandidate) {
            return null
        }
        val windows = service.windows.orEmpty()
        val candidateStillForeground = activeApplicationPackage(windows)
            ?.let { isGateExitPackage(it, applicationWindow = true) } == true
        if (!exitStabilizer.confirmAfterDelay(nowElapsedMs, candidateStillForeground)) {
            return null
        }
        return forceExit()
    }

    fun forceExit(): ForegroundDecision {
        exitStabilizer.reset()
        latchedTarget = null
        gateHasFocus = false
        lastDecision = ForegroundDecision(ForegroundClass.OTHER, null, false)
        return lastDecision
    }

    private fun gateExitCandidatePackage(
        event: AccessibilityEvent?,
        focused: AccessibilityWindowInfo?,
        windows: List<AccessibilityWindowInfo>,
    ): String? {
        if (focused?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return null
        val eventPackage = event
            ?.takeIf { it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED }
            ?.packageName
            ?.toString()
            ?.takeIf { isGateExitPackage(it) }
        if (eventPackage != null) return eventPackage
        return activeApplicationPackage(windows)?.takeIf { isGateExitPackage(it, applicationWindow = true) }
    }

    private fun classifyImeContext(windows: List<AccessibilityWindowInfo>): ForegroundDecision {
        val underlying = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: return ForegroundDecision(ForegroundClass.OTHER, null, false)
        val owner = ownerClassification(underlying) ?: PackageClassification(ForegroundClass.OTHER, null)
        return if (owner.activeTarget != null) {
            ForegroundDecision(owner.foregroundClass, owner.activeTarget, false)
        } else {
            ForegroundDecision(owner.foregroundClass, null, false)
        }
    }

    private fun classifyApplication(
        window: AccessibilityWindowInfo,
        event: AccessibilityEvent?,
    ): ForegroundDecision {
        val owner = ownerClassification(window) ?: packageClassification(event?.packageName?.toString())
        return if (owner.activeTarget != null && window.isFocused && window.isActive) {
            ForegroundDecision(owner.foregroundClass, owner.activeTarget, false)
        } else {
            ForegroundDecision(owner.foregroundClass, null, false)
        }
    }

    private fun classifyEventFallback(event: AccessibilityEvent?): ForegroundDecision {
        val owner = packageClassification(event?.packageName?.toString())
        return if (owner.activeTarget != null) {
            ForegroundDecision(owner.foregroundClass, owner.activeTarget, false)
        } else {
            ForegroundDecision(owner.foregroundClass, null, false)
        }
    }

    private fun packageClassification(packageName: String?): PackageClassification {
        val classification = classifyForegroundPackage(packageName, service.packageName)
        val activeTarget = protectedTargetForPackage(packageName)
        if (activeTarget != null && packageName != null) {
            lastProtectedPackageName = packageName
        }
        return PackageClassification(classification, activeTarget)
    }

    private fun ownerClassification(window: AccessibilityWindowInfo): PackageClassification? {
        val packageName = ownerPackage(window) ?: return null
        return packageClassification(packageName)
    }

    private fun activeApplicationPackage(
        windows: List<AccessibilityWindowInfo>,
    ): String? {
        val applications = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        // Our focusable gate can own Android's active/focused flags. The top
        // application window still identifies the app underneath it after Home
        // or an app switch; requiring isActive here leaves the gate latched.
        val foreground = applications.firstOrNull { it.isFocused }
            ?: applications.firstOrNull { it.isActive }
            ?: applications.maxByOrNull { it.layer }
        return foreground?.let(::ownerPackage)
    }

    private fun ownerPackage(window: AccessibilityWindowInfo): String? {
        val root: AccessibilityNodeInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) window.getRoot(0) else window.root
        } catch (_: Exception) {
            null
        } ?: return null

        val packageName = try {
            // This is the only node property the product reads. The hierarchy is never traversed.
            root.packageName?.toString()
        } finally {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                root.recycle()
            }
        }
        return packageName
    }

    private fun isGateExitPackage(packageName: String, applicationWindow: Boolean = false): Boolean =
        protectedTargetForPackage(packageName) == null &&
            (applicationWindow || packageName != service.packageName) &&
            packageName != SYSTEM_UI_PACKAGE

    private data class PackageClassification(
        val foregroundClass: ForegroundClass,
        val activeTarget: ProtectedTarget?,
    )

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}

internal fun classifyForegroundPackage(
    packageName: String?,
    restrictorPackageName: String,
): ForegroundClass = when {
    isSupportedBrowserPackage(packageName) -> ForegroundClass.BROWSER
    packageName == restrictorPackageName -> ForegroundClass.RESTRICTOR
    else -> ForegroundClass.OTHER
}
