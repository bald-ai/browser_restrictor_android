package com.michalkrsik.internetrestrictor.monitoring

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.michalkrsik.internetrestrictor.domain.CHROME_PACKAGE
import com.michalkrsik.internetrestrictor.domain.ForegroundClass

data class ForegroundDecision(
    val foregroundClass: ForegroundClass,
    val chromeActive: Boolean,
    val gateFocused: Boolean,
)

class ChromeForegroundMonitor(
    private val service: AccessibilityService,
) {
    private var latchedChromeContext = false
    private var gateVisible = false
    private var gateHasFocus = false
    private var lastDecision = ForegroundDecision(ForegroundClass.OTHER, false, false)

    fun setGateVisible(visible: Boolean) {
        gateVisible = visible
        if (!visible) gateHasFocus = false
    }

    fun setGateFocus(focused: Boolean) {
        gateHasFocus = focused
    }

    fun evaluate(event: AccessibilityEvent? = null): ForegroundDecision {
        val windows = service.windows.orEmpty()
        val focused = windows.firstOrNull { it.isFocused }
        val decision = when {
            gateVisible && latchedChromeContext -> {
                if (isDefinitiveGateExit(event, focused)) {
                    ForegroundDecision(ForegroundClass.OTHER, false, false)
                } else {
                    ForegroundDecision(
                        ForegroundClass.RESTRICTOR,
                        true,
                        gateHasFocus || focused?.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY,
                    )
                }
            }
            focused?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD -> classifyImeContext(windows)
            focused?.type == AccessibilityWindowInfo.TYPE_APPLICATION -> classifyApplication(focused, event)
            focused != null -> ForegroundDecision(ForegroundClass.OTHER, false, false)
            else -> {
                val activeApp = windows.firstOrNull {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive && it.isFocused
                }
                activeApp?.let { classifyApplication(it, event) } ?: classifyEventFallback(event)
            }
        }

        when {
            decision.foregroundClass == ForegroundClass.CHROME && decision.chromeActive -> {
                latchedChromeContext = true
            }
            decision.foregroundClass == ForegroundClass.RESTRICTOR && gateVisible && decision.chromeActive -> Unit
            else -> latchedChromeContext = false
        }
        lastDecision = decision
        return decision
    }

    private fun isDefinitiveGateExit(
        event: AccessibilityEvent?,
        focused: AccessibilityWindowInfo?,
    ): Boolean {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        if (focused?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return false
        val packageName = event.packageName?.toString() ?: return false
        return packageName != CHROME_PACKAGE &&
            packageName != service.packageName &&
            packageName != SYSTEM_UI_PACKAGE
    }

    private fun classifyImeContext(windows: List<AccessibilityWindowInfo>): ForegroundDecision {
        val underlying = windows.firstOrNull {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive
        } ?: return ForegroundDecision(ForegroundClass.OTHER, false, false)
        val owner = ownerClass(underlying) ?: ForegroundClass.OTHER
        return if (owner == ForegroundClass.CHROME) {
            ForegroundDecision(ForegroundClass.CHROME, true, false)
        } else {
            ForegroundDecision(owner, false, false)
        }
    }

    private fun classifyApplication(
        window: AccessibilityWindowInfo,
        event: AccessibilityEvent?,
    ): ForegroundDecision {
        val owner = ownerClass(window) ?: packageClass(event?.packageName?.toString())
        return if (owner == ForegroundClass.CHROME && window.isFocused && window.isActive) {
            ForegroundDecision(ForegroundClass.CHROME, true, false)
        } else {
            ForegroundDecision(owner, false, false)
        }
    }

    private fun classifyEventFallback(event: AccessibilityEvent?): ForegroundDecision {
        val owner = packageClass(event?.packageName?.toString())
        return if (owner == ForegroundClass.CHROME) {
            ForegroundDecision(owner, true, false)
        } else {
            ForegroundDecision(owner, false, false)
        }
    }

    private fun packageClass(packageName: String?): ForegroundClass = when (packageName) {
        CHROME_PACKAGE -> ForegroundClass.CHROME
        service.packageName -> ForegroundClass.RESTRICTOR
        else -> ForegroundClass.OTHER
    }

    private fun ownerClass(window: AccessibilityWindowInfo): ForegroundClass? {
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
        return when (packageName) {
            null -> null
            CHROME_PACKAGE -> ForegroundClass.CHROME
            service.packageName -> ForegroundClass.RESTRICTOR
            else -> ForegroundClass.OTHER
        }
    }

    private companion object {
        const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    }
}
