package com.michalkrsik.internetrestrictor.monitoring

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.michalkrsik.internetrestrictor.domain.ForegroundClass
import com.michalkrsik.internetrestrictor.domain.ProtectedTarget
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Local framework objects only: the stub never connects an accessibility service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32, 35], application = Application::class, shadows = [BrowserForegroundMonitorTest.PrefetchWindowShadow::class])
class BrowserForegroundMonitorTest {
    // Robolectric's window shadow implements getRoot() but omits the API 33 overload.
    @org.robolectric.annotation.Implements(AccessibilityWindowInfo::class)
    class PrefetchWindowShadow : org.robolectric.shadows.ShadowAccessibilityWindowInfo() {
        @org.robolectric.annotation.Implementation(minSdk = 33)
        protected fun getRoot(prefetchingStrategy: Int): AccessibilityNodeInfo? = super.getRoot()
    }
    class StubService : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }
    private val service = Robolectric.buildService(StubService::class.java).get()
    private val monitor = BrowserForegroundMonitor(service)
    private val browser = "com.android.chrome"

    private fun window(pkg: String?, focused: Boolean = true, active: Boolean = true,
        type: Int = AccessibilityWindowInfo.TYPE_APPLICATION, layer: Int = 0): AccessibilityWindowInfo =
        AccessibilityWindowInfo.obtain().also { window ->
            shadowOf(window).apply {
                setType(type); setFocused(focused); setActive(active); setLayer(layer)
                if (pkg != null) setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
            }
        }
    private fun windows(vararg windows: AccessibilityWindowInfo) { shadowOf(service).setWindows(windows.toList()) }
    private fun event(pkg: String?, type: Int = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) =
        AccessibilityEvent.obtain(type).apply { packageName = pkg }
    private fun latch() {
        windows(window(browser))
        assertEquals(ProtectedTarget.BROWSER_POOL, monitor.evaluate(nowElapsedMs = 0).activeTarget)
        monitor.setGateVisible(true)
    }

    @Test fun focusedActiveApplicationOwnsTheTargetAndInactiveWindowsCannot() {
        windows(window(browser))
        assertEquals(ForegroundDecision(ForegroundClass.BROWSER, ProtectedTarget.BROWSER_POOL, false), monitor.evaluate())
        assertEquals(browser, monitor.lastProtectedPackageName)
        windows(window(browser, active = false))
        assertEquals(ForegroundDecision(ForegroundClass.BROWSER, null, false), monitor.evaluate())
        windows(window("org.example.reader"))
        assertEquals(ForegroundDecision(ForegroundClass.OTHER, null, false), monitor.evaluate())
    }

    @Test fun absentRootsFallBackToEventsButUnknownEventsDoNotInventTargets() {
        windows(window(null))
        assertEquals(ProtectedTarget.BROWSER_POOL, monitor.evaluate(event(browser)).activeTarget)
        windows()
        assertEquals(ProtectedTarget.YOUTUBE, monitor.evaluate(event("com.google.android.youtube")).activeTarget)
        assertNull(monitor.evaluate(event(null)).activeTarget)
        assertNull(monitor.evaluate().activeTarget)
        assertEquals(ForegroundClass.RESTRICTOR, monitor.evaluate(event(service.packageName)).foregroundClass)
    }

    @Test fun inputMethodUsesActiveApplicationAndDoesNotTreatItsOwnPackageAsTheOwner() {
        windows(window("keyboard", type = AccessibilityWindowInfo.TYPE_INPUT_METHOD), window(browser, focused = false))
        assertEquals(ProtectedTarget.BROWSER_POOL, monitor.evaluate().activeTarget)
        windows(window("keyboard", type = AccessibilityWindowInfo.TYPE_INPUT_METHOD), window("reader", focused = false))
        assertNull(monitor.evaluate().activeTarget)
        windows(window("keyboard", type = AccessibilityWindowInfo.TYPE_INPUT_METHOD))
        assertNull(monitor.evaluate().activeTarget)
        windows(window("keyboard", type = AccessibilityWindowInfo.TYPE_INPUT_METHOD), window(null, focused = false))
        assertNull(monitor.evaluate().activeTarget)
    }

    @Test fun systemWindowsNeverAcquireAnApplicationTarget() {
        windows(window(browser, type = AccessibilityWindowInfo.TYPE_SYSTEM))
        assertNull(monitor.evaluate(event(browser)).activeTarget)
    }

    @Test fun visibleGateKeepsItsTargetAndFocusUntilAStableExit() {
        latch()
        windows(window(service.packageName, type = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY), window(browser, focused = false))
        assertEquals(ForegroundDecision(ForegroundClass.RESTRICTOR, ProtectedTarget.BROWSER_POOL, true), monitor.evaluate(nowElapsedMs = 10))
        windows(window("reader"))
        assertEquals(ProtectedTarget.BROWSER_POOL, monitor.evaluate(nowElapsedMs = 20).activeTarget)
        assertTrue(monitor.hasPendingGateExit)
        assertNull(monitor.confirmPendingGateExit(319))
        assertNotNull(monitor.confirmPendingGateExit(320))
        assertFalse(monitor.hasPendingGateExit)
        assertNull(monitor.confirmPendingGateExit(500))
    }

    @Test fun returningToBrowserCancelsPendingExitAndExplicitFocusIsPreserved() {
        latch()
        monitor.setGateFocus(true)
        windows(window("reader"))
        assertTrue(monitor.evaluate(nowElapsedMs = 10).gateFocused)
        windows(window(browser))
        assertNull(monitor.confirmPendingGateExit(400))
        assertFalse(monitor.hasPendingGateExit)
        assertEquals(ProtectedTarget.BROWSER_POOL, monitor.evaluate(nowElapsedMs = 500).activeTarget)
        monitor.setGateVisible(false)
        assertFalse(monitor.evaluate().gateFocused)
    }

    @Test fun switchingProtectedTargetsChangesOwnershipWithoutAcceptingAnExit() {
        latch()
        windows(window("com.google.android.youtube"))
        assertEquals(ForegroundDecision(ForegroundClass.OTHER, ProtectedTarget.YOUTUBE, false), monitor.evaluate())
        assertEquals("com.google.android.youtube", monitor.lastProtectedPackageName)
    }

    @Test fun keyboardAndSystemUiNoiseCannotReleaseTheGate() {
        latch()
        windows(window("keyboard", type = AccessibilityWindowInfo.TYPE_INPUT_METHOD), window(browser, focused = false))
        monitor.evaluate(event("reader"), 0)
        assertFalse(monitor.hasPendingGateExit)
        windows(window(browser))
        monitor.evaluate(event("com.android.systemui"), 400)
        monitor.evaluate(event(service.packageName), 800)
        monitor.evaluate(event("reader", AccessibilityEvent.TYPE_VIEW_CLICKED), 1200)
        assertFalse(monitor.hasPendingGateExit)
        assertEquals(ProtectedTarget.BROWSER_POOL, monitor.evaluate(nowElapsedMs = 1600).activeTarget)
    }

    @Test fun repeatedExternalWindowEventsReleaseOnlyAtTheStabilityBoundary() {
        latch()
        windows(window(browser))
        assertNotNull(monitor.evaluate(event("reader"), 100).activeTarget)
        assertNotNull(monitor.evaluate(event("reader"), 399).activeTarget)
        assertNull(monitor.evaluate(event("reader"), 400).activeTarget)
        assertNull(monitor.confirmPendingGateExit(500))
    }

    @Test fun highestApplicationLayerProvidesOwnershipWhenOverlayOwnsFocus() {
        latch()
        windows(window(service.packageName, type = AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY),
            window(browser, focused = false, active = false, layer = 1),
            window("reader", focused = false, active = false, layer = 2))
        assertNotNull(monitor.evaluate(nowElapsedMs = 0).activeTarget)
        assertNotNull(monitor.confirmPendingGateExit(300))
        assertNull(monitor.forceExit().activeTarget)
    }
}
