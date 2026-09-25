package com.michalkrsik.internetrestrictor.v1

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.util.Consumer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.michalkrsik.internetrestrictor.AppContainer
import com.michalkrsik.internetrestrictor.data.ConfigurationJson
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.fixtures.FixtureApplication
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.MockedStatic
import org.mockito.Mockito.*
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.time.Duration
import java.util.concurrent.Executor

/** CameraX dependencies emit simulated events; no camera, encoder, or recording is opened. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = FixtureApplication::class)
class V1VideoActivityTest {
    private val app = RuntimeEnvironment.getApplication() as FixtureApplication
    private val config = AppConfiguration(revision = 1)
    private val task = config.tasks.first { it.verifier == "video" }
    private val provider = mock(ProcessCameraProvider::class.java)
    private lateinit var providerStatic: MockedStatic<ProcessCameraProvider>
    private lateinit var controller: ActivityController<V1VideoActivity>
    private lateinit var activity: V1VideoActivity
    private val server = MockWebServer()
    private var failUpload = false
    private var failAttempt = false
    private var previewResponse = false
    private var failCamera = false
    private var callback: Consumer<VideoRecordEvent>? = null
    private val recorder = mock(Recorder::class.java)
    private val pending = mock(PendingRecording::class.java)
    private val recording = mock(Recording::class.java)
    private val capture = mock(VideoCapture::class.java) as VideoCapture<Recorder>
    private var errorEvent = false
    private var writeBytes = true

    @Before fun setup() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val response = when {
                    request.path == "/api/v1/installations" -> JSONObject().put("installationId", "fixture").put("token", "fixture")
                    request.path == "/api/v1/config" -> JSONObject().put("revision", 1).put("appliedRevision", 1).put("config", ConfigurationJson.configurationJson(config))
                    request.path == "/api/v1/attempts" -> {
                        if (failAttempt) return MockResponse().setResponseCode(503).setBody("{\"error\":\"fixture attempt unavailable\"}")
                        JSONObject(request.body.readUtf8())
                    }
                    request.path!!.endsWith("/video") -> {
                        if (failUpload) return MockResponse().setResponseCode(503).setBody("{\"error\":\"fixture offline\"}")
                        if (previewResponse) JSONObject().put("preview", true).put("measuredUnits", 10).put("reason", "Fixture accepted")
                        else ConfigurationJson.taskReceiptJson(TaskMeasurementReceipt("verified", task.id, 1, task, 10, app.clock.wallMs - 1000, app.clock.wallMs))
                    }
                    else -> JSONObject()
                }
                return MockResponse().setBody(response.toString())
            }
        }
        server.start()
        val old = app.container
        app.container = AppContainer(app, app.fixtureScope, app.clock, repository = old.repository,
            v1 = V1Coordinator(app, old.repository, app.fixtureScope, V1Api(app, server.url("/").toString()), app.clock::wallTimeMs))
        app.container.repository.applyConfiguration(config)
        providerStatic = mockStatic(ProcessCameraProvider::class.java)
        providerStatic.`when`<ListenableFuture<ProcessCameraProvider>> { ProcessCameraProvider.getInstance(any(Context::class.java)) }
            .thenAnswer { if (failCamera) Futures.immediateFailedFuture<ProcessCameraProvider>(IllegalStateException("fixture camera unavailable")) else Futures.immediateFuture(provider) }
        `when`(provider.hasCamera(any(CameraSelector::class.java))).thenReturn(true)
        `when`(capture.output).thenReturn(recorder)
        `when`(recorder.prepareRecording(any(Context::class.java), any(FileOutputOptions::class.java))).thenReturn(pending)
        `when`(pending.start(any(Executor::class.java), any())).thenAnswer { invocation ->
            callback = invocation.getArgument(1)
            if (writeBytes) ReflectionHelpers.getField<File>(activity, "currentFile").writeBytes(byteArrayOf(1, 2, 3))
            recording
        }
        doAnswer { finalizeEvent(); null }.`when`(recording).stop()
    }
    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        if (::providerStatic.isInitialized) providerStatic.close()
        app.fixtureScope.cancel()
        server.shutdown()
    }
    private fun mount(preview: String? = null, taskId: String = task.id) {
        controller = Robolectric.buildActivity(V1VideoActivity::class.java, Intent(app, V1VideoActivity::class.java)
            .putExtra("taskId", taskId).putExtra("previewAttemptId", preview)).setup()
        activity = controller.get(); tick()
    }
    private fun phase() = ReflectionHelpers.callInstanceMethod<String>(activity, "getPhase")
    private fun status() = ReflectionHelpers.callInstanceMethod<String>(activity, "getStatus")
    private fun call(name: String) { ReflectionHelpers.callInstanceMethod<Unit>(activity, name); tick() }
    private fun tick(ms: Long = 1) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun await(condition: () -> Boolean) {
        repeat(600) { tick(); if (condition()) return; Thread.sleep(5) }
        fail("Simulated recording did not reach expected state: ${phase()} / ${status()}")
    }
    private fun preview(id: String) = JSONObject().put("attemptId", id).put("taskId", task.id).put("preview", true)
        .put("configRevision", 1).put("startedAt", app.clock.wallMs).put("task", ConfigurationJson.taskJson(task))
        .also { File(app.filesDir, "v1-preview-attempts/$id.json").writeText(it.toString()) }
    private fun prepareCapture() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        call("startCamera")
        ReflectionHelpers.setField(activity, "capture", capture)
    }
    private fun begin() {
        call("begin")
        await { phase() == "countdown" || phase() == "recording" }
        tick(3000)
        await { phase() == "recording" }
    }
    private fun finalizeEvent() {
        val event = mock(VideoRecordEvent.Finalize::class.java)
        `when`(event.hasError()).thenReturn(errorEvent)
        callback!!.accept(event)
    }

    @Test fun refusedAttemptDoesNotStartCountdownOrCreateVideoEvidence() {
        mount(); prepareCapture(); failAttempt = true
        call("begin"); await { phase() == "error" }
        assertTrue(status().contains("fixture attempt unavailable"))
        assertNull(callback)
        assertNull(ReflectionHelpers.getField<File?>(activity, "currentFile"))
        verify(pending, never()).start(any(Executor::class.java), any())
    }

    @Test fun capturePreparationFailureWithoutAMessageReturnsToErrorWithoutUploading() {
        mount(); prepareCapture()
        `when`(recorder.prepareRecording(any(Context::class.java), any(FileOutputOptions::class.java)))
            .thenThrow(IllegalStateException())
        call("begin"); await { phase() == "countdown" || phase() == "error" }
        tick(3000); await { phase() == "error" }
        assertEquals("Connect to the internet to start a video.", status())
        assertNull(callback)
        verify(pending, never()).start(any(Executor::class.java), any())
        assertFalse(ReflectionHelpers.getField<File>(activity, "currentFile").exists())
        assertTrue(File(app.filesDir, "v1-evidence").listFiles()!!.isEmpty())
        assertTrue(app.container.repository.snapshot.value.taskReceipts.isEmpty())
    }

    @Test fun missingPreviewAndInvalidTaskCloseWithoutOpeningACamera() {
        mount("missing-preview")
        assertTrue(activity.isFinishing)
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("Test recording unavailable"))
        assertNull(callback)
        controller.pause().stop().destroy()
        mount(taskId = "missing-task")
        assertTrue(activity.isFinishing)
        assertNull(callback)
    }

    @Test fun alreadyGrantedPermissionInitializesCameraAndRecoveredPreviewDoesNotReopenIt() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        mount()
        verify(provider).bindToLifecycle(any(), any(CameraSelector::class.java), any(), any())
        controller.pause().stop().destroy()
        preview("interrupted-test")
        app.container.v1.claimPreviewRecording("interrupted-test")
        clearInvocations(provider)
        mount("interrupted-test")
        assertEquals("preview_interrupted", phase())
        verify(provider, never()).bindToLifecycle(any(), any(CameraSelector::class.java), any(), any())
    }

    @Test fun cameraPermissionAndUnavailableCameraCannotStartARecording() {
        mount()
        call("begin")
        assertEquals(Manifest.permission.CAMERA, shadowOf(activity).lastRequestedPermission.requestedPermissions.single())
        assertNull(callback)
        failCamera = true; prepareCapture()
        assertTrue(status().contains("Camera unavailable"))
        ReflectionHelpers.setField(activity, "capture", null)
        call("begin"); assertEquals("ready", phase())
        call("stopRecording"); assertEquals("ready", phase())
    }

    @Test fun stopSavesEvidenceBeforeUploadAndSuccessfulRetryDeletesTheTemporaryVideo() {
        mount(); prepareCapture(); failUpload = true
        begin(); assertEquals("recording", phase())
        val file = ReflectionHelpers.getField<File>(activity, "currentFile")
        assertTrue(file.exists())
        ReflectionHelpers.callInstanceMethod<Unit>(activity, "stopRecording")
        // Check the synchronous stop contract before advancing asynchronous upload/UI work.
        assertEquals(1, mockingDetails(recording).invocations.count { it.method.name == "stop" })
        await { phase() == "error" }
        assertTrue(status().contains("Recording saved for retry"))
        assertTrue(file.exists())
        assertTrue(File(app.filesDir, "v1-evidence").listFiles()!!.any { it.extension == "json" })
        failUpload = false
        call("submit"); await { phase() == "result" }
        assertTrue(status().startsWith("Verified"))
        assertFalse(file.exists())
        assertEquals(1, app.container.repository.snapshot.value.taskReceipts.size)
        verify(recording).stop()
    }

    @Test fun emptyFailedCaptureDoesNotUploadAndCountdownIsCancelledOnLeaving() {
        mount(); prepareCapture(); errorEvent = true; writeBytes = false
        begin(); finalizeEvent()
        assertEquals("error", phase())
        assertEquals("Recording failed. Try again.", status())
        assertTrue(File(app.filesDir, "v1-evidence").listFiles()!!.isEmpty())
        call("begin"); await { phase() == "countdown" }
        controller.pause().stop()
        assertEquals("ready", phase())
        assertTrue(status().contains("Recording interrupted"))
        tick(1000) // The suspended countdown observes cancellation before resuming capture.
        verify(pending, times(1)).start(any(Executor::class.java), any())
        controller.start().resume()
    }

    @Test fun previewOwnershipPersistsAcrossInterruptionAndCompletedCaptureReturnsToReview() {
        preview("one-test"); previewResponse = true
        mount("one-test"); prepareCapture()
        `when`(provider.hasCamera(any(CameraSelector::class.java))).thenReturn(false)
        call("startCamera")
        ReflectionHelpers.setField(activity, "capture", capture)
        begin(); assertEquals("recording", phase())
        tick(30_000); await { phase() == "result" }
        assertTrue(status().contains("No rewards granted"))
        assertTrue(app.container.repository.snapshot.value.taskReceipts.isEmpty())
        call("begin"); assertEquals("result", phase())
        call("retryPreview"); await { phase() == "result" }
    }
}
