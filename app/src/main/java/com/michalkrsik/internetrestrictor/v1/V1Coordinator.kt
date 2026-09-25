package com.michalkrsik.internetrestrictor.v1

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.michalkrsik.internetrestrictor.data.RestrictorRepository
import com.michalkrsik.internetrestrictor.data.ConfigurationJson
import com.michalkrsik.internetrestrictor.domain.TaskDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class V1Coordinator(
    private val context: Context,
    private val repository: RestrictorRepository,
    private val scope: CoroutineScope,
    val api: V1Api = V1Api(context),
    private val wallTimeMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val submissionLock = Mutex()
    private val _status = MutableStateFlow("Connecting to your setup…")
    val status = _status.asStateFlow()
    private val pendingDir = File(context.filesDir, "v1-evidence").apply { mkdirs() }
    private val previewDir = File(context.filesDir, "v1-preview-attempts").apply { mkdirs() }
    private val previews = PreviewRecordingStore(previewDir, pendingDir)
    private var lastStatisticsSent = 0L
    private var lastStatisticsKey = ""

    fun start() {
        scope.launch { sync(); retryPending() }
        // Dashboard and YouTube can remain open without the blocker's fast ticker.
        // Wake at the user's actual next reset so visible balances and access
        // change at the boundary even when there is no new interaction.
        scope.launch {
            repository.snapshot
                .filter { it.isLoaded && it.hasAppliedServerConfiguration }
                .map { snapshot ->
                    com.michalkrsik.internetrestrictor.domain.PeriodCalculator.nextDailyReset(
                        snapshot.wallNowMs, java.time.ZoneId.systemDefault(), snapshot.configuration.settings,
                    )
                }
                .distinctUntilChanged()
                .collectLatest { boundary ->
                    delay((boundary - wallTimeMs()).coerceAtLeast(1L))
                    repository.tick(android.os.SystemClock.elapsedRealtime(), wallTimeMs())
                }
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { scope.launch { sync(); retryPending() } }
        })
    }

    suspend fun sync(): Boolean = lock.withLock {
        try {
            repository.snapshot.first { it.isLoaded }
            api.bootstrap()
            val envelope = api.request("/config")
            val config = ConfigurationJson.parseConfiguration(envelope)
            if (repository.snapshot.value.appliedConfigRevision != config.revision || repository.snapshot.value.configuration != config) {
                check(repository.applyConfiguration(config)) { "Your phone could not save this setup. Try again." }
            }
            check(repository.snapshot.value.appliedConfigRevision == config.revision) { "Setup is still being applied." }
            if (envelope.optLong("appliedRevision", -1) < config.revision) api.request("/ack", JSONObject().put("revision", config.revision))
            val snapshot = repository.snapshot.value
            val statistics = com.michalkrsik.internetrestrictor.data.AgentStatistics.build(snapshot)
            val statisticsKey = "${snapshot.stateRevision}:${snapshot.appliedConfigRevision}:${snapshot.budgetSnapshots.firstOrNull()?.usedMs}:${snapshot.canonicalMode}"
            if (statisticsKey != lastStatisticsKey || wallTimeMs() - lastStatisticsSent >= 15_000) {
                api.request("/statistics", statistics)
                lastStatisticsSent = wallTimeMs()
                lastStatisticsKey = statisticsKey
            }
            _status.value = "Your setup is up to date"
            true
        } catch (error: Exception) {
            _status.value = "Offline · using saved setup. ${error.message.orEmpty().take(100)}"
            false
        }
    }

    suspend fun newAttempt(task: TaskDefinition, allowOffline: Boolean): JSONObject {
        val snapshot = repository.snapshot.value
        check(snapshot.appliedConfigRevision >= 0) { "Connect once to load your setup before recording." }
        check(snapshot.configuration.tasks.any { it == task && !it.paused }) { "This task changed. Reopen it to use the new rules." }
        val attempt = JSONObject().put("attemptId", UUID.randomUUID().toString()).put("taskId", task.id)
            .put("startedAt", wallTimeMs()).put("configRevision", snapshot.appliedConfigRevision)
        try { api.bootstrap(); api.request("/attempts", attempt) }
        catch (error: Exception) { if (!allowOffline) throw error }
        return attempt
    }

    suspend fun newPreviewAttempt(proposalId: String, version: Int, taskId: String): JSONObject {
        check(!V1GpsService.state.value.recording && !V1GpsService.state.value.acquiring) { "Finish your current GPS recording before testing another task." }
        api.bootstrap()
        val current = api.request("/config")
        val request = JSONObject().put("attemptId", UUID.randomUUID().toString()).put("taskId", taskId)
            .put("startedAt", wallTimeMs()).put("configRevision", current.getLong("revision"))
            .put("proposalId", proposalId).put("proposalVersion", version)
        val attempt = api.request("/attempts", request)
        check(attempt.optBoolean("preview")) { "The server did not create a test recording." }
        val task = ConfigurationJson.parseTask(attempt.getJSONObject("task"))
        check(task.id == taskId && task.validationError() == null) { "The proposed task could not be loaded." }
        val id = attempt.getString("attemptId")
        check(Regex("[a-zA-Z0-9_-]{1,128}").matches(id)) { "Invalid test recording identity." }
        File(previewDir, "$id.json").writeText(attempt.toString())
        return attempt
    }

    fun previewAttempt(id: String?): JSONObject? = previews.read(id)
    fun previewResult(id: String?): JSONObject? = previews.result(id)
    fun previewStatus(id: String?): PreviewRecordingStatus = previews.status(id)
    fun claimPreviewRecording(id: String): JSONObject = previews.claim(id)

    suspend fun retryPreview(id: String): JSONObject {
        previews.result(id)?.let { return it }
        check(previews.status(id) == PreviewRecordingStatus.WAITING_UPLOAD) { "There is no saved recording to upload. Return to review to start a new test." }
        check(V1GpsService.activeAttemptId != id) { "Stop the recording before submitting it." }
        val evidence = JSONObject(File(pendingDir, "$id.json").readText())
        if (evidence.optString("kind") == "gps" && !evidence.optBoolean("complete")) {
            evidence.put("complete", true)
            persistPending(id, evidence)
        }
        return submit(evidence)
    }

    suspend fun saveVideo(attempt: JSONObject, file: File, endedAt: Long): JSONObject {
        val id = attempt.getString("attemptId")
        val evidence = JSONObject(attempt.toString()).put("kind", "video").put("file", file.absolutePath).put("endedAt", endedAt)
        persistPending(id, evidence)
        return submit(evidence)
    }

    fun persistGps(attempt: JSONObject, points: JSONArray, endedAt: Long, complete: Boolean) {
        val evidence = JSONObject(attempt.toString()).put("kind", "gps").put("points", points)
            .put("endedAt", endedAt).put("complete", complete).put("timeZone", java.util.TimeZone.getDefault().id)
        persistPending(attempt.getString("attemptId"), evidence)
    }

    suspend fun finishGps(attemptId: String): JSONObject = submit(JSONObject(File(pendingDir, "$attemptId.json").readText()))

    private fun persistPending(id: String, evidence: JSONObject) {
        synchronized(pendingDir) {
            val temporary = File(pendingDir, "$id.tmp")
            temporary.writeText(evidence.toString())
            check(temporary.renameTo(File(pendingDir, "$id.json"))) { "Recording could not be saved." }
        }
    }

    private suspend fun submit(evidence: JSONObject): JSONObject = submissionLock.withLock {
        val id = evidence.getString("attemptId")
        if (evidence.optBoolean("preview")) previews.result(id)?.let { cached ->
            File(pendingDir, "$id.json").delete()
            if (evidence.optString("kind") == "video") File(evidence.getString("file")).delete()
            return@withLock cached
        }
        api.bootstrap()
        val result = if (evidence.getString("kind") == "video") {
            api.video(id, File(evidence.getString("file")), evidence.getLong("endedAt"))
        } else api.request("/attempts/$id/gps", evidence)
        val json = result.optJSONObject("receipt") ?: result
        val preview = evidence.optBoolean("preview") || json.optBoolean("preview")
        if (preview) {
            // Testing a proposed task never reaches the native reward ledger, even if a response omits its flag.
            json.put("preview", true)
            File(previewDir, "$id-result.json").writeText(json.toString())
        } else {
            val receipt = ConfigurationJson.parseTaskReceipt(json)
            check(repository.applyTaskReceipt(receipt)) { "Verified activity could not be saved. Retry to keep your reward." }
        }
        File(pendingDir, "$id.json").delete()
        if (evidence.getString("kind") == "video") File(evidence.getString("file")).delete()
        _status.value = if (preview) "Test recording checked · no rewards granted" else "Activity verified and saved"
        scope.launch { sync() }
        json
    }

    suspend fun correct(attemptId: String, value: Double) {
        require(value.isFinite() && value in 0.0..100_000.0) { "Enter a valid correction." }
        val snapshot = repository.snapshot.value
        val saved = snapshot.taskReceipts.find { it.measurement.attemptId == attemptId }
        check(saved != null && saved.measurement.id in snapshot.modifiableTaskReceiptIds && !saved.measurement.manualCompletion) { "This recording can no longer be corrected." }
        val result = api.request("/attempts/$attemptId/correction", JSONObject().put("value", value))
        val json = result.optJSONObject("receipt") ?: result
        check(repository.applyTaskReceipt(ConfigurationJson.parseTaskReceipt(json))) { "Correction could not be saved." }
        sync()
    }

    suspend fun confirmGpsFallback(attemptId: String) {
        val snapshot = repository.snapshot.value
        val saved = snapshot.taskReceipts.find { it.measurement.attemptId == attemptId }
        check(saved != null && saved.measurement.id in snapshot.modifiableTaskReceiptIds && saved.measurement.fallbackEligible && !saved.measurement.manualCompletion) { "This recording can no longer earn a manual completion." }
        val result = api.request("/attempts/$attemptId/fallback", JSONObject().put("completed", true))
        val json = result.optJSONObject("receipt") ?: result
        check(repository.applyTaskReceipt(ConfigurationJson.parseTaskReceipt(json))) { "Completion could not be saved." }
        sync()
    }

    suspend fun retryPending() = withContext(Dispatchers.IO) {
        pendingDir.listFiles { f -> f.extension == "json" }?.forEach { file ->
            try {
                val evidence = JSONObject(file.readText())
                // A process restart closes the captured portion. Never resume or count downtime.
                if (evidence.getString("kind") == "gps" && !evidence.optBoolean("complete", false)) {
                    if (V1GpsService.activeAttemptId == evidence.getString("attemptId")) return@forEach
                    evidence.put("complete", true)
                    persistPending(evidence.getString("attemptId"), evidence)
                }
                submit(evidence)
            } catch (_: Exception) { _status.value = "Recording saved · waiting to verify. Tap Retry when connected." }
        }
    }
}
