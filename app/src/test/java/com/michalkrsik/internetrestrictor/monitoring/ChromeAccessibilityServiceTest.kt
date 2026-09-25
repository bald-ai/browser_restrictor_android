package com.michalkrsik.internetrestrictor.monitoring

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.view.accessibility.*
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.fixtures.FixtureApplication
import com.michalkrsik.internetrestrictor.overlay.OverlayCoordinator
import com.michalkrsik.internetrestrictor.ui.challenge.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter.from
import java.lang.reflect.Proxy
import java.time.Duration

/** No service is registered with Android; fake windows never attach to a display. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FixtureApplication::class, shadows = [BrowserForegroundMonitorTest.PrefetchWindowShadow::class])
class ChromeAccessibilityServiceTest {
    private val app = RuntimeEnvironment.getApplication() as FixtureApplication
    private val repository get() = app.container.repository
    private val windows = linkedSetOf<View>()
    private val controllers = mutableListOf<ServiceController<ChromeAccessibilityService>>()
    private val manager = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
        when (method.name) {
            "addView" -> { windows += args!![0] as View; null }
            "removeViewImmediate" -> { windows -= args!![0] as View; null }
            "updateViewLayout" -> null
            "toString" -> "FixtureWindowManager"
            else -> error("Unexpected window request: ${method.name}")
        }
    } as WindowManager
    @Before fun setup() = runBlocking {
        repository.awaitLoaded(); repository.applyConfiguration(AppConfiguration(revision = 1))
        TaskPickerTransition.pending.value = false
    }
    @After fun close() {
        controllers.reversed().forEach { it.destroy() }
        TaskPickerTransition.pending.value = false
        app.fixtureScope.cancel()
    }
    private fun service(connect: Boolean = true): ChromeAccessibilityService {
        val controller = ServiceController.of(ChromeAccessibilityService(manager), Intent()).create()
        controllers += controller
        val service = controller.get()
        if (connect) { call(service, "onServiceConnected"); tick() }
        return service
    }
    private fun call(service: ChromeAccessibilityService, name: String) { ReflectionHelpers.callInstanceMethod<Unit>(service, name) }
    private fun tick(ms: Long = 1) { app.clock.advance(ms); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun foreground(service: ChromeAccessibilityService, pkg: String) {
        val window = AccessibilityWindowInfo.obtain()
        shadowOf(window).apply {
            setType(AccessibilityWindowInfo.TYPE_APPLICATION); setActive(true); setFocused(true)
            setRoot(AccessibilityNodeInfo.obtain().apply { packageName = pkg })
        }
        shadowOf(service).setWindows(listOf(window))
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply { packageName = pkg })
        tick()
    }
    private fun coordinator(service: ChromeAccessibilityService) = ReflectionHelpers.getField<OverlayCoordinator>(service, "overlayCoordinator")

    @Test fun interruptedSetupCleansEachIndependentlyInitializedOverlay() {
        val service = service(connect = false)
        call(service, "removeOverlays")
        val gate = com.michalkrsik.internetrestrictor.overlay.GateOverlayController(app, manager, repository,
            app.fixtureScope, {}, {}, {}, { throw it })
        val badge = com.michalkrsik.internetrestrictor.overlay.BadgeOverlayController(app, manager, repository, { throw it })
        val monitor = BrowserForegroundMonitor(service)
        ReflectionHelpers.setField(service, "gateController", gate)
        gate.show(); assertTrue(gate.isShowing)
        call(service, "removeOverlays"); assertFalse(gate.isShowing)
        ReflectionHelpers.setField(service, "badgeController", badge)
        ReflectionHelpers.setField(service, "foregroundMonitor", monitor)
        badge.show(); monitor.setGateVisible(true)
        call(service, "removeOverlays")
        assertFalse(badge.isShowing)
        assertTrue(windows.isEmpty())
        assertFalse(ReflectionHelpers.getField<Boolean>(monitor, "gateVisible"))
    }

    @Test fun connectionEventsScreenChangesAndInterruptionUpdateRepositoryAndReleaseWindows() {
        val service = service(connect = false)
        service.onAccessibilityEvent(null)
        service.onConfigurationChanged(Configuration())
        call(service, "onServiceConnected"); tick()
        assertTrue(repository.snapshot.value.serviceConnected)
        foreground(service, "com.android.chrome")
        assertEquals(ProtectedTarget.BROWSER_POOL, repository.snapshot.value.activeTarget)
        assertTrue(windows.isNotEmpty())
        service.onConfigurationChanged(Configuration())
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onGateFocusChanged", from(Boolean::class.javaPrimitiveType, true))
        tick(200)
        assertTrue(ReflectionHelpers.getField<RuntimeSignals>(repository, "runtime").gateFocused)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); tick()
        service.onInterrupt(); tick()
        assertFalse(repository.snapshot.value.serviceConnected)
        assertTrue(windows.isEmpty())
        service.onUnbind(null)
    }

    @Test fun oldServiceDisconnectCannotInvalidateItsReplacement() {
        val old = service()
        val replacement = service()
        old.onUnbind(null); tick()
        assertTrue(repository.snapshot.value.serviceConnected)
        replacement.onInterrupt(); tick()
        assertFalse(repository.snapshot.value.serviceConnected)
    }

    @Test fun leavingIsBoundedAndOverlaysStaySuppressedUntilARealExitAndReentry() {
        val service = service()
        foreground(service, "com.android.chrome")
        call(service, "leaveBrowser"); call(service, "leaveBrowser")
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_HOME), shadowOf(service).globalActionsPerformed)
        assertTrue(coordinator(service).isLeaving)
        tick(500)
        assertFalse(coordinator(service).isLeaving)
        assertEquals(OverlayCoordinator.Target.HIDDEN, coordinator(service).target)
        tick(100)
        foreground(service, "org.example.reader"); tick(300)
        assertNull(repository.snapshot.value.activeTarget)
        foreground(service, "com.android.chrome")
        assertNotEquals(OverlayCoordinator.Target.HIDDEN, coordinator(service).target)
    }

    @Test fun confirmedHomeAndChallengeWatchdogsFinishHandoffsWithoutLaunchingAnActivity() {
        val service = service()
        foreground(service, "com.android.chrome")
        call(service, "leaveBrowser")
        foreground(service, "org.example.reader"); tick(300)
        assertFalse(coordinator(service).isLeaving)
        foreground(service, "com.android.chrome")
        call(service, "openExerciseChallenge"); call(service, "openExerciseChallenge")
        assertTrue(TaskPickerTransition.pending.value)
        assertTrue(shadowOf(service).nextStartedActivity.component!!.className.endsWith("MainActivity"))
        assertNull(shadowOf(service).nextStartedActivity)
        tick(1_500)
        assertFalse(TaskPickerTransition.pending.value)

    }
}
