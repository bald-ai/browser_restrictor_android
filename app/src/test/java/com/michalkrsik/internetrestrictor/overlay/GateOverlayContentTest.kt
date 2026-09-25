package com.michalkrsik.internetrestrictor.overlay

import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.fixtures.FixtureApplication
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

/** The fake manager hosts content inside a JVM-only activity; no overlay or system window is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35], application = FixtureApplication::class)
class GateOverlayContentTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app = RuntimeEnvironment.getApplication() as FixtureApplication
    private val host = Robolectric.buildActivity(ComponentActivity::class.java).setup()
    private val repository = app.container.repository
    private var tasks = 0
    private var leaves = 0
    private val manager = Proxy.newProxyInstance(WindowManager::class.java.classLoader, arrayOf(WindowManager::class.java)) { _, method, args ->
        when (method.name) {
            "addView" -> { host.get().setContentView(args!![0] as View); null }
            "removeViewImmediate" -> { host.get().setContentView(FrameLayout(host.get())); null }
            else -> error("Unexpected manager call ${method.name}")
        }
    } as WindowManager
    private val gate = GateOverlayController(host.get(), manager, repository, app.fixtureScope,
        { tasks++ }, {}, { leaves++ }, {}, { throw it })
    @Before fun setup() = runBlocking {
        repository.awaitLoaded()
        repository.updateDeviceAccess(true, true, true, false)
        repository.updateServiceRuntime(ForegroundClass.BROWSER, ProtectedTarget.BROWSER_POOL,
            gateFocused = true, serviceConnected = true)
    }
    @After fun close() { gate.hide(); host.pause().stop().destroy(); app.fixtureScope.cancel() }
    @Test fun visibleGateActionsUseCurrentRepositoryRevisionAndTokens() {
        compose.runOnUiThread { gate.show() }
        val revision = repository.snapshot.value.stateRevision
        compose.onNodeWithText("Try again").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(repository.snapshot.value.stateRevision > revision) }
        compose.onNodeWithText("CHOOSE A TASK").performScrollTo().performClick()
        assertEquals(1, tasks)
        compose.onNodeWithText("Leave browser").performScrollTo().performClick()
        assertEquals(1, leaves)
        compose.runOnIdle { runBlocking { assertTrue(repository.recordPushupRepetitions(5)); repository.setTheme(ThemePreference.LIGHT) } }
        val label = repository.snapshot.value.configuration.settings.emergencyUnlockLabel
        compose.onNodeWithText(label).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(UnlockSource.EMERGENCY, repository.snapshot.value.activeUnlock!!.source) }
        compose.runOnIdle { runBlocking { assertTrue(repository.cancelActiveUnlock()); repository.setTheme(ThemePreference.DARK) } }
        compose.onNodeWithText("Unlock · 5 minutes").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(5 * MINUTE_MS, repository.snapshot.value.waitSnapshot!!.selectedDurationMs) }
    }
}
