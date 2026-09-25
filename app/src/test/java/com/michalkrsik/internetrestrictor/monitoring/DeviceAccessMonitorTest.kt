package com.michalkrsik.internetrestrictor.monitoring

import android.content.Context
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.michalkrsik.internetrestrictor.fixtures.FixtureApplication
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContextImpl
import java.util.concurrent.Executor

/** Advanced-protection state and callbacks are modeled locally, never queried on a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = FixtureApplication::class, shadows = [DeviceAccessMonitorTest.ProtectionShadow::class])
class DeviceAccessMonitorTest {
    @Implements(AdvancedProtectionManager::class, minSdk = 36)
    class ProtectionShadow {
        var enabled = false
        var failQuery = false
        var failRegister = false
        var failUnregister = false
        var callback: AdvancedProtectionManager.Callback? = null
        var removals = 0
        @Implementation fun isAdvancedProtectionEnabled(): Boolean {
            if (failQuery) throw IllegalStateException("fixture query unavailable")
            return enabled
        }
        @Implementation fun registerAdvancedProtectionCallback(executor: Executor, listener: AdvancedProtectionManager.Callback) {
            if (failRegister) throw SecurityException("fixture unavailable")
            callback = listener
        }
        @Implementation fun unregisterAdvancedProtectionCallback(listener: AdvancedProtectionManager.Callback) {
            removals++
            if (failUnregister) throw IllegalStateException("fixture removed")
            assertSame(callback, listener); callback = null
        }
    }
    private val app = RuntimeEnvironment.getApplication() as FixtureApplication
    private val contextShadow = Shadow.extract<ShadowContextImpl>(app.baseContext)
    private val manager = Shadow.newInstanceOf(AdvancedProtectionManager::class.java)
    private val protection = Shadow.extract<ProtectionShadow>(manager)
    private val monitor = DeviceAccessMonitor(app, app.container)
    private val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
    @Before fun setup() { contextShadow.setSystemService(Context.ADVANCED_PROTECTION_SERVICE, manager) }
    @After fun close() { monitor.onStop(owner); app.fixtureScope.cancel() }

    @Test fun lifecycleRegistrationPublishesChangesAndStopUnregistersExactlyOnce() {
        monitor.onStart(owner)
        assertNotNull(protection.callback)
        assertFalse(app.container.accessChecker.isAdvancedProtectionEnabled())
        protection.enabled = true
        Settings.Secure.putString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            android.content.ComponentName(app, ChromeAccessibilityService::class.java).flattenToString())
        protection.callback!!.onAdvancedProtectionChanged(true)
        assertTrue(app.container.accessChecker.isAdvancedProtectionEnabled())
        assertTrue(app.container.repository.snapshot.value.accessibilityEnabled)
        monitor.onStop(owner); monitor.onStop(owner)
        assertEquals(1, protection.removals)
        assertNull(protection.callback)
    }

    @Test fun unavailableQueriesAndCallbackFailuresDoNotClaimAProtectionBlock() {
        protection.failQuery = true; protection.failRegister = true
        monitor.onStart(owner)
        assertFalse(app.container.accessChecker.isAdvancedProtectionEnabled())
        assertNull(protection.callback)
        monitor.onStop(owner)
        assertEquals(0, protection.removals)
        protection.failQuery = false; protection.failRegister = false
        monitor.onStart(owner)
        protection.failUnregister = true
        monitor.onStop(owner); monitor.onStop(owner)
        assertEquals(1, protection.removals)
        contextShadow.removeSystemService(Context.ADVANCED_PROTECTION_SERVICE)
        assertFalse(app.container.accessChecker.isAdvancedProtectionEnabled())
        monitor.onStart(owner); monitor.onStop(owner)
    }
}
