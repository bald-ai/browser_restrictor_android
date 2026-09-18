package com.michalkrsik.internetrestrictor.ui.challenge

import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.michalkrsik.internetrestrictor.diagnostics.LocalEventLog
import com.michalkrsik.internetrestrictor.domain.ExerciseId
import com.michalkrsik.internetrestrictor.domain.ExerciseMetric
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_PACKAGE_NAME
import com.michalkrsik.internetrestrictor.ui.sprint.SprintChallengeActivity
import com.michalkrsik.internetrestrictor.ui.sprint.SprintLocationService
import com.michalkrsik.internetrestrictor.ui.sprint.SprintPhase
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Run against a test emulator: no camera verification or model calls are made. */
class ChallengeRecoveryTest {
    @get:Rule val composeRule = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Before fun permissions() {
        org.junit.Assume.assumeFalse("Legacy sprint UI is retired in V1", com.michalkrsik.internetrestrictor.BuildConfig.IS_V1)
        for (permission in listOf(Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS)) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission")
                .use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
    }

    @Test fun recordingAndPendingResultSurviveActivityRecreation() {
        ActivityScenario.launch(SprintChallengeActivity::class.java).use { scenario ->
            val service = attachedService(scenario)
            scenario.onActivity { service.startRecording(0) }
            assertTrue(service.isCurrentlyRecording())
            scenario.recreate()
            val reattached = attachedService(scenario)
            assertSame(service, reattached)
            assertTrue("Recreation stopped the recording", reattached.isCurrentlyRecording())
            assertEquals(SprintPhase.RECORDING, reattached.phase)
            clickText("Close")
            assertTrue("Close discarded the recording without confirmation", reattached.isCurrentlyRecording())
            clickText("Keep sprint")
            scenario.onActivity { reattached.stopRecording() }
            val pending = checkNotNull(reattached.saveState)
            assertTrue(pending.recordingId.isNotBlank())
            scenario.recreate()
            val resultService = attachedService(scenario)
            assertSame(service, resultService)
            assertEquals(SprintPhase.RESULT, resultService.phase)
            assertEquals(pending.recordingId, resultService.saveState?.recordingId)
            assertEquals(pending.result, resultService.saveState?.result)
        }
    }

    @Test fun sprintNotificationReentryPreservesOriginAndExplicitLaunchUpdatesIt() {
        val launch = Intent(context, SprintChallengeActivity::class.java)
            .putExtra(SprintChallengeActivity.EXTRA_RETURN_PACKAGE, YOUTUBE_PACKAGE_NAME)
        ActivityScenario.launch<SprintChallengeActivity>(launch).use { scenario ->
            val service = attachedService(scenario)
            var original: SprintChallengeActivity? = null
            scenario.onActivity {
                original = it
                it.startActivity(Intent(it, SprintChallengeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertSame(original, it)
                assertEquals(YOUTUBE_PACKAGE_NAME, it.intent.getStringExtra(SprintChallengeActivity.EXTRA_RETURN_PACKAGE))
            }
            assertSame(service, attachedService(scenario))
            scenario.recreate()
            scenario.onActivity {
                assertEquals(YOUTUBE_PACKAGE_NAME, it.intent.getStringExtra(SprintChallengeActivity.EXTRA_RETURN_PACKAGE))
                it.startActivity(Intent(it, SprintChallengeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(SprintChallengeActivity.EXTRA_RETURN_PACKAGE, "com.android.chrome"))
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity {
                assertEquals("com.android.chrome", it.intent.getStringExtra(SprintChallengeActivity.EXTRA_RETURN_PACKAGE))
            }
        }
    }

    @Test fun diagnosticFailureDoesNotEscape() {
        val root = File(context.cacheDir, "diagnostic-failure-test").apply { mkdirs() }
        val wrapped = object : ContextWrapper(context) { override fun getFilesDir(): File = root }
        File(root, "restrictor_diagnostics.log").mkdirs() // A directory cannot be read/written as a log file.
        try { LocalEventLog(wrapped).record("Storage failed") }
        finally { root.deleteRecursively() }
    }

    private fun clickText(text: String) {
        composeRule.onNodeWithText(text).performClick()
    }

    private fun attachedService(scenario: ActivityScenario<SprintChallengeActivity>): SprintLocationService {
        val connected = CountDownLatch(1)
        var result: SprintLocationService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                result = (binder as SprintLocationService.LocalBinder).service
                connected.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        scenario.onActivity {
            assertTrue(it.bindService(Intent(it, SprintLocationService::class.java), connection, Context.BIND_AUTO_CREATE))
        }
        assertTrue("Service did not bind", connected.await(10, TimeUnit.SECONDS))
        scenario.onActivity { it.unbindService(connection) }
        return checkNotNull(result)
    }
}
