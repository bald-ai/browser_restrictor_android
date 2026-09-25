package com.michalkrsik.internetrestrictor.v1

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import androidx.datastore.core.DataStore
import com.michalkrsik.internetrestrictor.data.ConfigurationJson
import com.michalkrsik.internetrestrictor.data.RestrictorRepository
import com.michalkrsik.internetrestrictor.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class V1CoordinatorTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("UTC")
    private val store = Store()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    // Follow-up syncs are queued, giving tests control over their timing.
    private val coordinatorScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher())
    private lateinit var context: Context
    private lateinit var repository: RestrictorRepository
    private lateinit var coordinator: V1Coordinator
    private val server = MockWebServer()
    private var config = AppConfiguration(revision = 1)
    private var ack = -1L
    private var failPath: String? = null
    private var responseReceipt: JSONObject? = null
    private var attemptResponse: JSONObject? = null
    private val paths = CopyOnWriteArrayList<String>()
    private val bodies = CopyOnWriteArrayList<Pair<String, String>>()
    private val pending get() = File(folder.root, "v1-evidence")
    private val previews get() = File(folder.root, "v1-preview-attempts")
    private val videoTask get() = config.tasks.first { it.verifier == "video" && it.metric == "repetitions" }
    private val gpsTask get() = config.tasks.first { it.verifier == "gps" }

    private class Store : DataStore<PersistedState> {
        override val data = MutableStateFlow(PersistedState())
        var fail = false
        override suspend fun updateData(transform: suspend (PersistedState) -> PersistedState): PersistedState {
            if (fail) throw IOException("Disk unavailable")
            return transform(data.value).also { data.value = it }
        }
    }

    @Before fun setup() = runBlocking {
        context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = folder.root
        }
        context.getSharedPreferences("v1_installation", Context.MODE_PRIVATE).edit().clear().commit()
        repository = RestrictorRepository(timeSource = object : TimeSource {
            override fun wallTimeMs() = now
            override fun elapsedRealtimeMs() = 1000L
            override fun zoneId() = zone
        }, applicationScope = repositoryScope, dataStoreOverride = store)
        repository.awaitLoaded()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.removePrefix("/api/v1")
                paths += path
                val body = request.body.readUtf8()
                bodies += path to body
                if (path == failPath) return MockResponse().setResponseCode(503).setBody("{\"error\":\"Fixture offline\"}")
                val response = when {
                    path == "/installations" -> JSONObject().put("installationId", "fixture").put("token", "fixture-token")
                    path == "/config" -> JSONObject().put("revision", config.revision).put("appliedRevision", ack).put("config", ConfigurationJson.configurationJson(config))
                    path == "/ack" -> JSONObject().also { ack = JSONObject(body).getLong("revision") }
                    path == "/statistics" -> JSONObject()
                    path == "/attempts" -> attemptResponse ?: JSONObject(body)
                    path.startsWith("/attempts/") -> responseReceipt ?: JSONObject().put("preview", true).put("measuredUnits", 12)
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(response.toString()).setHeader("Content-Type", "application/json")
            }
        }
        server.start()
        coordinator = V1Coordinator(context, repository, coordinatorScope, V1Api(context, server.url("/").toString()), { now })
        V1GpsService.activeAttemptId = null
        V1GpsService.state.value = V1GpsState()
    }

    @After fun teardown() {
        repositoryScope.cancel(); coordinatorScope.cancel(); server.shutdown()
        V1GpsService.activeAttemptId = null; V1GpsService.state.value = V1GpsState()
    }

    private fun measurement(id: String, task: TaskDefinition = gpsTask, value: Long = task.unitsPerCredit) =
        TaskMeasurementReceipt(id, task.id, config.revision, task, value, now - 1000, now,
            timeZone = "UTC", fallbackEligible = task.verifier == "gps")
    private fun attempt(id: String, task: TaskDefinition = gpsTask, preview: Boolean = false) = JSONObject()
        .put("attemptId", id).put("taskId", task.id).put("configRevision", config.revision).put("startedAt", now - 1000)
        .put("preview", preview).put("task", ConfigurationJson.taskJson(task))
    private suspend fun connected() { assertTrue(coordinator.sync()) }
    private suspend fun rejected(message: String, action: suspend () -> Unit) {
        try { action(); fail("Expected rejection: $message") }
        catch (error: IllegalStateException) { assertEquals(message, error.message) }
    }

    @Test fun serverPreviewResultCannotCreditANormalAttempt() = runBlocking {
        connected()
        val input = attempt("server-preview", preview = false)
        coordinator.persistGps(input, JSONArray(), now, complete = true)
        responseReceipt = JSONObject().put("preview", true).put("measuredUnits", 12)
        val result = coordinator.finishGps("server-preview")
        assertTrue(result.getBoolean("preview"))
        assertTrue(repository.snapshot.value.taskReceipts.isEmpty())
        assertEquals(0L, repository.snapshot.value.earnedBudgetRoundsToday)
    }

    @Test fun syncAppliesBeforeAcknowledgingAndThrottlesUnchangedStatistics() = runBlocking {
        connected()
        assertEquals(1L, repository.snapshot.value.appliedConfigRevision)
        assertEquals(listOf("/installations", "/config", "/ack", "/statistics"), paths.toList())
        assertEquals("Your setup is up to date", coordinator.status.value)
        connected()
        assertEquals(1, paths.count { it == "/statistics" })
        now += 15_000
        connected()
        assertEquals(2, paths.count { it == "/statistics" })
        assertEquals(1, paths.count { it == "/ack" })
        config = config.copy(revision = 2, settings = config.settings.copy(waitSeconds = 9))
        connected()
        assertEquals(9L, repository.snapshot.value.configuration.settings.waitSeconds)
        assertEquals(2L, ack)
    }

    @Test fun failedLocalConfigurationWriteNeverAcknowledgesTheNewRevision() = runBlocking {
        store.fail = true
        assertFalse(coordinator.sync())
        assertFalse(paths.contains("/ack"))
        assertTrue(coordinator.status.value.contains("could not save"))
        store.fail = false
        connected()
        assertEquals(1L, ack)
        failPath = "/config"
        assertFalse(coordinator.sync())
        assertEquals(1L, repository.snapshot.value.appliedConfigRevision)
        assertTrue(coordinator.status.value.contains("Fixture offline"))
    }

    @Test fun attemptsRequireCurrentTaskButGpsCanCaptureOfflineAfterInitialSync() = runBlocking {
        rejected("Connect once to load your setup before recording.") { coordinator.newAttempt(gpsTask, true) }
        connected()
        rejected("This task changed. Reopen it to use the new rules.") { coordinator.newAttempt(gpsTask.copy(name = "Old task"), true) }
        failPath = "/attempts"
        val offline = coordinator.newAttempt(gpsTask, true)
        assertEquals(gpsTask.id, offline.getString("taskId"))
        assertEquals(now, offline.getLong("startedAt"))
        rejected("Fixture offline") { coordinator.newAttempt(videoTask, false) }
    }

    @Test fun gpsEvidenceSurvivesFailedUploadAndAppliesExactlyOnceOnRetry() = runBlocking {
        connected()
        val evidence = attempt("gps")
        coordinator.persistGps(evidence, JSONArray().put(JSONObject().put("latitude", 1)), now, true)
        val file = File(pending, "gps.json")
        assertTrue(file.exists())
        failPath = "/attempts/gps/gps"
        rejected("Fixture offline") { coordinator.finishGps("gps") }
        assertTrue(file.exists())
        assertTrue(repository.snapshot.value.taskReceipts.isEmpty())
        failPath = null
        responseReceipt = JSONObject().put("receipt", ConfigurationJson.taskReceiptJson(measurement("gps")))
        coordinator.finishGps("gps")
        assertFalse(file.exists())
        assertEquals(1, repository.snapshot.value.taskReceipts.size)
        assertEquals(1L, repository.snapshot.value.earnedBudgetRoundsToday)
        coordinator.persistGps(evidence, JSONArray(), now, true)
        coordinator.finishGps("gps")
        assertEquals(1, repository.snapshot.value.taskReceipts.size)
        assertEquals(1L, repository.snapshot.value.earnedBudgetRoundsToday)
    }

    @Test fun verifiedEvidenceIsKeptUntilItsNativeRewardIsDurable() = runBlocking {
        connected()
        coordinator.persistGps(attempt("gps"), JSONArray(), now, true)
        responseReceipt = ConfigurationJson.taskReceiptJson(measurement("gps"))
        store.fail = true
        rejected("Verified activity could not be saved. Retry to keep your reward.") { coordinator.finishGps("gps") }
        assertTrue(File(pending, "gps.json").exists())
        assertTrue(repository.snapshot.value.taskReceipts.isEmpty())
        store.fail = false
        coordinator.finishGps("gps")
        assertFalse(File(pending, "gps.json").exists())
        assertEquals(1L, repository.snapshot.value.earnedBudgetRoundsToday)
    }

    @Test fun previewNeverCreditsNativeRewardsEvenIfServerOmitsPreviewFlag() = runBlocking {
        connected()
        val spec = attempt("preview", preview = true)
        File(previews, "preview.json").writeText(spec.toString())
        assertEquals(PreviewRecordingStatus.READY, coordinator.previewStatus("preview"))
        coordinator.claimPreviewRecording("preview")
        assertEquals(PreviewRecordingStatus.INTERRUPTED, coordinator.previewStatus("preview"))
        coordinator.persistGps(spec, JSONArray(), now, false)
        responseReceipt = ConfigurationJson.taskReceiptJson(measurement("preview"))
        V1GpsService.activeAttemptId = "preview"
        rejected("Stop the recording before submitting it.") { coordinator.retryPreview("preview") }
        V1GpsService.activeAttemptId = null
        val result = coordinator.retryPreview("preview")
        assertTrue("Preview responses must explicitly retain the no-reward marker", result.optBoolean("preview"))
        assertEquals(PreviewRecordingStatus.COMPLETE, coordinator.previewStatus("preview"))
        assertTrue(repository.snapshot.value.taskReceipts.isEmpty())
        assertEquals(0L, repository.snapshot.value.earnedBudgetRoundsToday)
        val requests = paths.size
        assertEquals(result.toString(), coordinator.retryPreview("preview").toString())
        assertEquals(requests, paths.size)
        coordinator.persistGps(spec, JSONArray(), now, true)
        assertEquals(result.toString(), coordinator.finishGps("preview").toString())
        assertEquals("Cached evidence must not be submitted again", requests, paths.size)
        assertFalse(File(pending, "preview.json").exists())
        assertTrue(JSONObject(bodies.first { it.first == "/attempts/preview/gps" }.second).getBoolean("complete"))
    }

    @Test fun interruptedPendingCaptureClosesWithoutResumingAndActiveCaptureIsSkipped() = runBlocking {
        connected()
        coordinator.persistGps(attempt("active"), JSONArray(), now, false)
        coordinator.persistGps(attempt("interrupted", preview = true), JSONArray(), now, false)
        File(pending, "corrupt.json").writeText("not json")
        V1GpsService.activeAttemptId = "active"
        coordinator.retryPending()
        assertTrue(File(pending, "active.json").exists())
        assertFalse(File(pending, "interrupted.json").exists())
        assertFalse(paths.contains("/attempts/active/gps"))
        assertTrue(JSONObject(bodies.first { it.first == "/attempts/interrupted/gps" }.second).getBoolean("complete"))
        assertTrue(File(pending, "corrupt.json").exists())
    }

    @Test fun videoAndPendingMetadataAreDeletedOnlyAfterSuccessfulVerification() = runBlocking {
        connected()
        val video = folder.newFile("fixture.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        failPath = "/attempts/video/video"
        rejected("Fixture offline") { coordinator.saveVideo(attempt("video", videoTask, true), video, now) }
        assertTrue(video.exists()); assertTrue(File(pending, "video.json").exists())
        failPath = null
        coordinator.retryPending()
        assertFalse(video.exists()); assertFalse(File(pending, "video.json").exists())
        assertNotNull(coordinator.previewResult("video"))
        assertTrue(repository.snapshot.value.taskReceipts.isEmpty())
    }

    @Test fun previewCreationChecksCaptureOwnershipTaskAndServerIdentity() = runBlocking {
        V1GpsService.state.value = V1GpsState(acquiring = true)
        rejected("Finish your current GPS recording before testing another task.") { coordinator.newPreviewAttempt("proposal", 2, gpsTask.id) }
        V1GpsService.state.value = V1GpsState()
        attemptResponse = attempt("preview", preview = true)
        val created = coordinator.newPreviewAttempt("proposal", 2, gpsTask.id)
        assertEquals(created.toString(), coordinator.previewAttempt("preview").toString())
        assertEquals(PreviewRecordingStatus.READY, coordinator.previewStatus("preview"))
        val request = JSONObject(bodies.last { it.first == "/attempts" }.second)
        assertEquals("proposal", request.getString("proposalId")); assertEquals(2, request.getInt("proposalVersion"))
        attemptResponse = attempt("wrong", preview = false)
        rejected("The server did not create a test recording.") { coordinator.newPreviewAttempt("proposal", 2, gpsTask.id) }
        attemptResponse = attempt("../escape", preview = true)
        rejected("Invalid test recording identity.") { coordinator.newPreviewAttempt("proposal", 2, gpsTask.id) }
        rejected("There is no saved recording to upload. Return to review to start a new test.") { coordinator.retryPreview("missing") }
    }

    @Test fun wrappedCorrectionsAndUnwrappedCompletionsBothUpdateTheDurableLedger() = runBlocking {
        connected()
        val recording = measurement("alternate-envelope", value = 1)
        assertTrue(repository.applyTaskReceipt(recording))
        responseReceipt = JSONObject().put("receipt", ConfigurationJson.taskReceiptJson(recording.copy(correctedUnits = 3, correctedUnitsText = "3")))
        coordinator.correct("alternate-envelope", 3.0)
        assertEquals(3L, repository.snapshot.value.taskReceipts.single().value)
        responseReceipt = ConfigurationJson.taskReceiptJson(recording.copy(manualCompletion = true))
        coordinator.confirmGpsFallback("alternate-envelope")
        assertTrue(repository.snapshot.value.taskReceipts.single().measurement.manualCompletion)
        assertEquals(1L, repository.snapshot.value.earnedBudgetRoundsToday)
    }

    @Test fun correctionsAndFallbackRequireCurrentEligibleReceipts() = runBlocking {
        connected()
        rejected("This recording can no longer be corrected.") { coordinator.correct("missing", 2.0) }
        rejected("This recording can no longer earn a manual completion.") { coordinator.confirmGpsFallback("missing") }
        val recording = measurement("gps", value = 1)
        assertTrue(repository.applyTaskReceipt(recording))
        responseReceipt = ConfigurationJson.taskReceiptJson(recording.copy(correctedUnits = 2, correctedUnitsText = "2"))
        coordinator.correct("gps", 2.0)
        assertEquals(2L, repository.snapshot.value.taskReceipts.single().value)
        responseReceipt = JSONObject().put("receipt", ConfigurationJson.taskReceiptJson(recording.copy(manualCompletion = true)))
        coordinator.confirmGpsFallback("gps")
        assertTrue(repository.snapshot.value.taskReceipts.single().measurement.manualCompletion)
        assertEquals(1L, repository.snapshot.value.earnedBudgetRoundsToday)
        rejected("This recording can no longer be corrected.") { coordinator.correct("gps", 3.0) }
        rejected("This recording can no longer earn a manual completion.") { coordinator.confirmGpsFallback("gps") }
    }
}
