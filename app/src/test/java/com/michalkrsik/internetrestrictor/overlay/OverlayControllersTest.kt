package com.michalkrsik.internetrestrictor.overlay

import android.app.Application
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.datastore.core.DataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import com.michalkrsik.internetrestrictor.data.RestrictorRepository
import com.michalkrsik.internetrestrictor.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

/** The fake WindowManager records operations only; it never attaches a system overlay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OverlayControllersTest {
    private val context = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repository = RestrictorRepository(timeSource = FakeTimeSource(1_790_250_000_000), applicationScope = scope,
        dataStoreOverride = object : DataStore<PersistedState> {
            override val data = MutableStateFlow(PersistedState())
            override suspend fun updateData(transform: suspend (PersistedState) -> PersistedState) = transform(data.value).also { data.value = it }
        })
    private val attached = linkedMapOf<View, WindowManager.LayoutParams>()
    private val calls = mutableListOf<String>()
    private val focuses = mutableListOf<Boolean>()
    private val failures = mutableListOf<Throwable>()
    private var failAdd = false
    private var failRemove = false
    private var failUpdate = false
    private var leaves = 0
    private val manager = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
        when (method.name) {
            "addView" -> {
                calls += "add"
                if (failAdd) throw IllegalArgumentException("fixture attach failure")
                attached[args!![0] as View] = args[1] as WindowManager.LayoutParams
                null
            }
            "removeViewImmediate" -> {
                calls += "remove"
                attached.remove(args!![0])
                if (failRemove) throw IllegalArgumentException("already detached")
                null
            }
            "updateViewLayout" -> {
                calls += "update"
                if (failUpdate) throw IllegalArgumentException("detached during drag")
                attached[args!![0] as View] = args[1] as WindowManager.LayoutParams
                null
            }
            "toString" -> "FixtureWindowManager"
            else -> error("Unexpected framework request: ${method.name}")
        }
    } as WindowManager
    private val gate = GateOverlayController(context, manager, repository, scope, {}, {}, { leaves++ }, focuses::add, failures::add)
    private val badge = BadgeOverlayController(context, manager, repository, failures::add)

    @After fun close() { gate.hide(); badge.hide(); scope.cancel() }

    @Test fun badgeDragRoundsOffsetsClampsBothEdgesAndIgnoresDetachedGestures() {
        fun move(delta: Float) { ReflectionHelpers.callInstanceMethod<Unit>(badge, "moveBy",
            ReflectionHelpers.ClassParameter.from(Float::class.javaPrimitiveType, delta)) }
        move(100f)
        assertTrue(attached.isEmpty())
        badge.show()
        val (view, params) = attached.entries.single().let { it.key to it.value }
        view.layout(0, 0, 100, 100)
        params.y = 50; params.x = 22
        move(12.9f)
        assertEquals(62, params.y); assertEquals(0, params.x)
        move(-1000f); assertEquals(0, params.y)
        move(100000f); assertEquals(context.resources.displayMetrics.heightPixels - 100, params.y)
        failUpdate = true; move(-10f)
        assertTrue(badge.isShowing)
        badge.hide(); move(5f)
        assertTrue(attached.isEmpty())
    }

    @Test fun gateIsFullSizeOpaqueAndFocusedAndBackDispatchesTheLeaveAction() {
        gate.hide(); gate.recreateForConfigurationChange()
        gate.show(); gate.show()
        assertTrue(gate.isShowing)
        assertEquals(1, attached.size)
        val (view, params) = attached.entries.single().let { it.key to it.value }
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, params.height)
        assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, params.type)
        assertEquals(PixelFormat.OPAQUE, params.format)
        assertEquals(0, params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        assertTrue(view.isFocusableInTouchMode)
        val owner = view.findViewTreeLifecycleOwner()!!
        assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
        // The fixture never attaches a window, so dispatch its registered listener directly.
        val listenerInfo = ReflectionHelpers.callInstanceMethod<Any>(view, "getListenerInfo")
        val keyListener = ReflectionHelpers.getField<View.OnKeyListener>(listenerInfo, "mOnKeyListener")
        assertFalse(keyListener.onKey(view, KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)))
        assertFalse(keyListener.onKey(view, KeyEvent.KEYCODE_ENTER, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)))
        assertTrue(keyListener.onKey(view, KeyEvent.KEYCODE_BACK, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK)))
        assertEquals(1, leaves)
        gate.hide(reportFocusLoss = false)
        assertEquals(Lifecycle.State.DESTROYED, owner.lifecycle.currentState)
        assertFalse(gate.isShowing)
        assertTrue(focuses.isEmpty())
        assertTrue(attached.isEmpty())
    }

    @Test fun failedAttachDoesNotPublishShowingAndAlreadyDetachedRemovalStillCleansUp() {
        failAdd = true
        gate.show(); badge.show()
        assertFalse(gate.isShowing); assertFalse(badge.isShowing)
        assertEquals(2, failures.size)
        failAdd = false
        gate.show(); badge.show()
        failRemove = true
        gate.hide(); badge.hide()
        assertFalse(gate.isShowing); assertFalse(badge.isShowing)
        assertEquals(listOf(false), focuses)
        assertTrue(attached.isEmpty())
        gate.hide(); badge.hide()
    }

    @Test fun badgeClampsPositionAfterDisplayChangesAndToleratesDetachedUpdates() {
        badge.refreshForConfigurationChange()
        badge.show(); badge.show()
        val (view, params) = attached.entries.single().let { it.key to it.value }
        assertEquals(PixelFormat.TRANSLUCENT, params.format)
        assertTrue(params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertEquals((context.resources.displayMetrics.heightPixels * .2f).toInt(), params.y)
        view.layout(0, 0, 100, 100)
        params.y = Int.MAX_VALUE
        badge.refreshForConfigurationChange()
        assertEquals((context.resources.displayMetrics.heightPixels - 100).coerceAtLeast(0), params.y)
        params.y = -100
        badge.refreshForConfigurationChange()
        assertEquals(0, params.y)
        failUpdate = true
        badge.refreshForConfigurationChange()
        assertTrue(badge.isShowing)
        gate.show()
        gate.recreateForConfigurationChange()
        assertTrue(gate.isShowing)
        assertTrue(focuses.isEmpty())
    }

    @Test fun coordinatorMaintainsExclusiveOwnershipAndBoundedLeaveTransitions() {
        val visible = mutableListOf<Boolean>()
        val coordinator = OverlayCoordinator(gate, badge, visible::add)
        coordinator.render(OverlayCoordinator.Target.GATE)
        assertTrue(gate.isShowing); assertFalse(badge.isShowing)
        val count = calls.size
        coordinator.render(OverlayCoordinator.Target.GATE)
        assertEquals(count, calls.size)
        coordinator.render(OverlayCoordinator.Target.BADGE)
        assertFalse(gate.isShowing); assertTrue(badge.isShowing)
        coordinator.onConfigurationChanged()
        coordinator.render(OverlayCoordinator.Target.CHALLENGE)
        assertFalse(gate.isShowing); assertFalse(badge.isShowing)
        coordinator.render(OverlayCoordinator.Target.GATE)
        coordinator.beginLeaving(); coordinator.beginLeaving()
        coordinator.render(OverlayCoordinator.Target.BADGE)
        assertTrue(coordinator.isLeaving); assertTrue(gate.isShowing)
        coordinator.hideWhileLeaving()
        assertFalse(gate.isShowing)
        coordinator.finishLeaving()
        assertEquals(OverlayCoordinator.Target.HIDDEN, coordinator.target)
        coordinator.hideWhileLeaving()
        coordinator.render(OverlayCoordinator.Target.BADGE)
        coordinator.failOpen()
        assertEquals(OverlayCoordinator.Target.HIDDEN, coordinator.target)
        assertFalse(badge.isShowing)
        assertEquals(listOf(true, false, false, true, false, false, false, false), visible)
    }
}
