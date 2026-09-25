package com.michalkrsik.internetrestrictor.receivers

import android.content.BroadcastReceiver
import android.content.Intent
import com.michalkrsik.internetrestrictor.AppContainer
import com.michalkrsik.internetrestrictor.fixtures.FixtureApplication
import com.michalkrsik.internetrestrictor.monitoring.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.*
import org.robolectric.annotation.Config

/** Direct receiver dispatch, with a simulated async token and access checker. No OS broadcast. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FixtureApplication::class)
class SystemEventReceiverTest {
    private val app = RuntimeEnvironment.getApplication() as FixtureApplication
    private val failures = mutableListOf<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + CoroutineExceptionHandler { _, error -> failures += error })
    private val access = mock(DeviceAccessChecker::class.java)
    @Before fun setup() {
        val old = app.container
        app.container = AppContainer(app, scope, app.clock, old.eventLog, old.repository, access, old.v1)
    }
    @After fun close() { scope.cancel(); app.fixtureScope.cancel() }
    private fun deliver(action: String?): BroadcastReceiver.PendingResult {
        val receiver = SystemEventReceiver()
        val pending = mock(BroadcastReceiver.PendingResult::class.java)
        org.robolectric.util.ReflectionHelpers.setField(receiver, "mPendingResult", pending)
        receiver.onReceive(app, Intent().setAction(action))
        verify(pending).finish()
        assertNull(org.robolectric.util.ReflectionHelpers.getField<Any?>(receiver, "mPendingResult"))
        return pending
    }
    @Test fun clockBoundaryRefreshesAccessAndAlwaysFinishesTheAsyncToken() {
        `when`(access.read()).thenReturn(DeviceAccessSnapshot(true, true, true, false))
        deliver(Intent.ACTION_TIMEZONE_CHANGED)
        val state = app.container.repository.snapshot.value
        assertTrue(state.accessibilityEnabled); assertTrue(state.usageAccessGranted)
        assertTrue(app.container.eventLog.exportText().contains("System boundary: TIMEZONE_CHANGED"))
        assertTrue(failures.isEmpty())
        deliver(null)
    }
    @Test fun failedAccessQueryStillReleasesTheBroadcastToken() {
        `when`(access.read()).thenThrow(IllegalStateException("fixture unavailable"))
        deliver(Intent.ACTION_TIME_CHANGED)
        assertEquals("fixture unavailable", failures.single().message)
    }
}
