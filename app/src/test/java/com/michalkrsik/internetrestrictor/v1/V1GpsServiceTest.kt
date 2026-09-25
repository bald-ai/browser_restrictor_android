package com.michalkrsik.internetrestrictor.v1

import android.Manifest
import android.app.Application
import android.content.*
import android.location.Location
import android.location.LocationManager
import android.os.*
import androidx.datastore.core.DataStore
import com.michalkrsik.internetrestrictor.data.*
import com.michalkrsik.internetrestrictor.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
class V1GpsServiceTest {
    @get:Rule val folder = TemporaryFolder()
    private val application = RuntimeEnvironment.getApplication()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val server = MockWebServer()
    private lateinit var service: V1GpsService
    private lateinit var controller: ServiceController<V1GpsService>
    private lateinit var coordinator: V1Coordinator
    private lateinit var repository: RestrictorRepository
    private val config = AppConfiguration(revision = 1)
    private val task get() = config.tasks.first { it.gpsMode == "walk" }
    private var uploadFails = false
    private val pending get() = File(folder.root, "v1-evidence")
    private fun tick(ms: Long = 1) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }

    @Before fun setup() = runBlocking {
        V1GpsService.state.value = V1GpsState(); V1GpsService.activeAttemptId = null
        val context = object : ContextWrapper(application) { override fun getFilesDir() = folder.root }
        context.getSharedPreferences("v1_installation", Context.MODE_PRIVATE).edit().clear()
            .putString("id", "fixture").putString("token", "fixture").commit()
        repository = RestrictorRepository(timeSource = FakeTimeSource(System.currentTimeMillis()), applicationScope = scope,
            dataStoreOverride = object : DataStore<PersistedState> {
                override val data = MutableStateFlow(PersistedState())
                override suspend fun updateData(transform: suspend (PersistedState) -> PersistedState) = transform(data.value).also { data.value = it }
            })
        repository.awaitLoaded()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (uploadFails) {
                MockResponse().setResponseCode(503).setBody("{\"error\":\"fixture offline\"}")
            } else MockResponse().setBody("{\"preview\":true,\"measuredUnits\":12,\"diagnostics\":{\"speedExcluded\":1,\"gaps\":2,\"rejected\":3}}")
        }
        server.start()
        coordinator = V1Coordinator(context, repository, scope, V1Api(context, server.url("/").toString()))
        service = V1GpsService(GpsServiceDependencies(repository, coordinator, scope))
        controller = ServiceController.of(service, Intent()).create()
        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    @After fun cleanup() {
        controller.destroy(); scope.cancel(); server.shutdown()
        V1GpsService.activeAttemptId = null; V1GpsService.state.value = V1GpsState()
    }
    private fun start(preview: String? = null) {
        runBlocking { assertTrue(repository.applyConfiguration(config)) }
        service.onStartCommand(Intent().putExtra("taskId", task.id).putExtra("previewAttemptId", preview), 0, 1)
        tick()
    }
    private fun stop() { service.onStartCommand(Intent().setAction("stop"), 0, 2); tick() }
    private fun fix(meters: Double = 0.0, accuracy: Float? = 5f, speed: Float? = 1f, age: Long = 0, mock: Boolean = false) = Location("gps").apply {
        latitude = 50 + meters / 111195.0; longitude = 14.0
        time = 1_790_250_000_000L + SystemClock.elapsedRealtime(); elapsedRealtimeNanos = (SystemClock.elapsedRealtime() - age) * 1_000_000
        if (accuracy != null) this.accuracy = accuracy
        if (speed != null) this.speed = speed
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 31) setMock(mock)
        else org.robolectric.util.ReflectionHelpers.callInstanceMethod<Unit>(this, "setIsFromMockProvider",
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType, mock))
    }
    private fun awaitSubmission() {
        repeat(200) {
            tick()
            if (!V1GpsService.state.value.submitting) return
            Thread.sleep(5)
        }
        fail("Loopback verification did not complete")
    }
    private fun preview(id: String) {
        val spec = JSONObject().put("attemptId", id).put("taskId", task.id).put("preview", true)
            .put("configRevision", 1).put("startedAt", System.currentTimeMillis())
            .put("task", ConfigurationJson.taskJson(task))
        File(folder.root, "v1-preview-attempts/$id.json").writeText(spec.toString())
    }

    @Test fun startupRequiresSavedConfigurationTaskAndPreciseLocation() {
        assertNull(service.onBind(null))
        service.onStartCommand(null, 0, 1)
        assertTrue(V1GpsService.state.value.status.contains("Connect once"))
        start("missing")
        assertFalse(V1GpsService.state.value.acquiring)
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        start()
        assertTrue(V1GpsService.state.value.status.contains("Precise location"))
        assertFalse(V1GpsService.state.value.recording)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test fun acquisitionRejectsStaleFutureMockAndInaccurateFixesThenPersistsOnlyAcceptedEvidence() {
        start()
        assertTrue(V1GpsService.state.value.acquiring)
        service.onStartCommand(Intent().putExtra("taskId", "other"), 0, 3)
        assertEquals(task.id, V1GpsService.state.value.taskId)
        for (location in listOf(fix(age = 10_001), fix(age = -1), fix(mock = true), fix(accuracy = null), fix(accuracy = 26f))) {
            service.onLocationChanged(location)
            assertFalse(V1GpsService.state.value.recording)
        }
        service.onLocationChanged(fix(accuracy = 25f))
        assertTrue(V1GpsService.state.value.recording)
        val id = V1GpsService.activeAttemptId!!
        tick(10_000); service.onLocationChanged(fix(20.0, speed = null))
        val file = File(pending, "$id.json")
        val saved = JSONObject(file.readText())
        assertFalse(saved.getBoolean("complete"))
        assertEquals(2, saved.getJSONArray("points").length())
        assertTrue(V1GpsService.state.value.meters > 0)
        tick(1_000); service.onLocationChanged(fix(21.0, accuracy = 40f))
        assertTrue(V1GpsService.state.value.status.contains("Weak GPS"))
        uploadFails = true
        stop(); awaitSubmission()
        assertTrue(file.exists())
        assertTrue(JSONObject(file.readText()).getBoolean("complete"))
        assertTrue(V1GpsService.state.value.status.contains("Saved on your phone"))
        val finalState = V1GpsService.state.value
        service.onLocationChanged(fix(20.0)); stop()
        assertEquals(finalState, V1GpsService.state.value)
        assertNull(V1GpsService.activeAttemptId)
    }

    @Test fun previewClaimCannotBeReusedAndSuccessfulVerificationReturnsDiagnosticsWithoutReward() {
        preview("preview"); start("preview")
        assertTrue(V1GpsService.state.value.preview)
        service.onLocationChanged(fix())
        assertEquals("preview", V1GpsService.activeAttemptId)
        tick(5_000); service.onLocationChanged(fix(5.0))
        stop(); awaitSubmission()
        assertEquals(12.0, V1GpsService.state.value.meters, 0.0)
        assertTrue(V1GpsService.state.value.status.contains("No rewards granted"))
        assertTrue(V1GpsService.state.value.status.contains("1 fast sections, 2 gaps and 3 inaccurate"))
        assertFalse(File(pending, "preview.json").exists())
        assertTrue(repository.snapshot.value.taskReceipts.isEmpty())
        start("preview")
        assertTrue(V1GpsService.state.value.status.contains("already has a recording"))
        assertFalse(V1GpsService.state.value.acquiring)
    }

    @Test fun noSignalTimesOutAndStoppingBeforeFirstFixDoesNotCreateEvidence() {
        start()
        tick(121_000)
        assertFalse(V1GpsService.state.value.acquiring)
        assertTrue(V1GpsService.state.value.status.contains("No reliable GPS"))
        assertTrue(pending.listFiles()!!.isEmpty())
        stop()
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test fun destroyingAnActiveCaptureClosesTheEvidenceWithItsLastFixTime() {
        start(); service.onLocationChanged(fix())
        tick(2_000)
        val last = fix(2.0, accuracy = null, speed = null)
        service.onLocationChanged(last)
        val id = V1GpsService.activeAttemptId!!
        controller.destroy()
        val saved = JSONObject(File(pending, "$id.json").readText())
        assertTrue(saved.getBoolean("complete"))
        assertEquals(last.time, saved.getLong("endedAt"))
        assertEquals(999.0, saved.getJSONArray("points").getJSONObject(1).getDouble("accuracy"), 0.0)
        assertTrue(V1GpsService.state.value.status.contains("interrupted"))
        assertFalse(V1GpsService.state.value.recording)
    }
}
