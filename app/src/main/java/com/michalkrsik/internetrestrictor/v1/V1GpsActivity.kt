package com.michalkrsik.internetrestrictor.v1

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.michalkrsik.internetrestrictor.appContainer
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.michalkrsik.internetrestrictor.data.ConfigurationJson
import org.json.JSONObject

class V1GpsActivity : ComponentActivity() {
    private var permissionError by mutableStateOf(false)
    private var previewSpec: JSONObject? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true) startTracking() else permissionError = true
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previewId = intent.getStringExtra("previewAttemptId")
        previewSpec = appContainer.v1.previewAttempt(previewId)
        if (previewId != null && previewSpec == null) {
            android.widget.Toast.makeText(this, "Test recording unavailable. Start a new test from the review card.", android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }
        setContent {
            val snapshot by appContainer.repository.snapshot.collectAsState()
            val recordedState by V1GpsService.state.collectAsState()
            val selectedTaskId = if (recordedState.recording || recordedState.acquiring) recordedState.taskId else intent.getStringExtra("taskId")
            val previewTask = previewSpec?.getJSONObject("task")?.let(ConfigurationJson::parseTask)
            val task = previewTask ?: snapshot.configuration.tasks.firstOrNull { it.id == selectedTaskId }
            val gps = if (recordedState.taskId == selectedTaskId && recordedState.previewAttemptId == previewSpec?.getString("attemptId")) recordedState else V1GpsState(taskId = selectedTaskId.orEmpty(), preview = previewSpec != null)
            val latest = snapshot.taskReceipts.filter { it.measurement.taskId == task?.id }.maxByOrNull { it.measurement.finishedAtWallMs }
            val scope = rememberCoroutineScope()
            var fallbackError by remember { mutableStateOf<String?>(null) }
            var fallbackBusy by remember { mutableStateOf(false) }
            var testStatus by remember { mutableStateOf(appContainer.v1.previewStatus(previewId)) }
            var testResult by remember { mutableStateOf(appContainer.v1.previewResult(previewId)) }
            var retryingTest by remember { mutableStateOf(false) }
            LaunchedEffect(previewId) {
                if (previewId != null) while (true) {
                    testStatus = appContainer.v1.previewStatus(previewId)
                    testResult = appContainer.v1.previewResult(previewId)
                    delay(1_000)
                }
            }
            val testRecovered = previewId != null && !gps.recording && !gps.acquiring && !gps.submitting
            val displayStatus = if (testRecovered) when (testStatus) {
                PreviewRecordingStatus.COMPLETE -> "Test result: ${testResult?.opt("measuredUnits")} meters. ${testResult?.optString("reason").orEmpty()} No rewards granted."
                PreviewRecordingStatus.WAITING_UPLOAD -> "Your test recording is saved on this phone. It will be checked when connected, or you can retry now."
                PreviewRecordingStatus.INTERRUPTED, PreviewRecordingStatus.MISSING -> "This test recording was interrupted. Return to the review card to start a new test."
                PreviewRecordingStatus.READY -> gps.status
            } else gps.status
            RestrictorTheme(snapshot.settings.theme) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        TextButton(onClick = { finish() }) { Text(if (previewSpec != null) "‹ Back to review" else "‹ Back to your day") }
                        Text(task?.name ?: "GPS activity", style = MaterialTheme.typography.headlineLarge)
                        Text(if (previewSpec != null) "Test recording · your setup and rewards stay unchanged" else "${task?.unitsPerCredit ?: 0} meters = 1 ${snapshot.configuration.settings.creditName}")
                        Spacer(Modifier.weight(1f))
                        Text(if (testRecovered && testStatus == PreviewRecordingStatus.COMPLETE) "${testResult?.opt("measuredUnits")} m" else "${gps.meters.toLong()} m", style = MaterialTheme.typography.displayLarge)
                        Text("${gps.elapsedSeconds / 60}:${(gps.elapsedSeconds % 60).toString().padStart(2, '0')} elapsed · ${gps.accuracy?.toInt()?.let { "GPS ±${it}m" } ?: "Waiting for GPS"}")
                        if (gps.acquiring || gps.submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (permissionError) "Precise location permission is needed to verify distance." else displayStatus)
                        if (previewSpec == null && !gps.recording && !gps.acquiring && latest?.measurement?.fallbackEligible == true && !latest.measurement.manualCompletion && latest.measurement.id in snapshot.modifiableTaskReceiptIds) {
                            Text("GPS coverage was insufficient. Confirm only if you completed this task.")
                            OutlinedButton(enabled = !fallbackBusy, onClick = {
                                fallbackBusy = true
                                scope.launch { try { appContainer.v1.confirmGpsFallback(latest.measurement.attemptId) } catch (error: Exception) { fallbackError = error.message } finally { fallbackBusy = false } }
                            }) { Text(if (fallbackBusy) "Saving…" else "I completed this task · 1 credit") }
                        }
                        fallbackError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        Text(if (task?.gpsMode == "walk") "Walk anywhere. Vehicle-speed sections, drift and tracking gaps do not count. Stop when you finish; the 4-hour cap ends a forgotten recording." else "Run outdoors. Qualifying sprint sections must reach 15 km/h. GPS is verified after you stop; live distance is provisional.", style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.weight(1f))
                        if (gps.recording || gps.acquiring) {
                            Button(onClick = { startService(Intent(this@V1GpsActivity, V1GpsService::class.java).setAction("stop")) }, modifier = Modifier.fillMaxWidth()) { Text("Stop & verify") }
                            Text("You can lock your screen. Recording keeps going.", modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodySmall)
                        } else if (testRecovered && testStatus != PreviewRecordingStatus.READY) {
                            if (testStatus == PreviewRecordingStatus.WAITING_UPLOAD) {
                                Button(enabled = !retryingTest, onClick = {
                                    retryingTest = true
                                    scope.launch {
                                        try { appContainer.v1.retryPreview(previewId!!) }
                                        catch (error: Exception) { fallbackError = error.message }
                                        finally {
                                            testStatus = appContainer.v1.previewStatus(previewId)
                                            testResult = appContainer.v1.previewResult(previewId)
                                            retryingTest = false
                                        }
                                    }
                                }, modifier = Modifier.fillMaxWidth()) { Text(if (retryingTest) "Checking recording…" else "Retry saved recording") }
                            }
                            Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Back to review") }
                        } else if (!gps.submitting) Button(onClick = {
                            val requested = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                            if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
                            if (requested.all { ContextCompat.checkSelfPermission(this@V1GpsActivity, it) == PackageManager.PERMISSION_GRANTED }) startTracking()
                            else permissions.launch(requested.toTypedArray())
                        }, modifier = Modifier.fillMaxWidth()) { Text("Start recording") }
                    }
                }
            }
        }
    }
    private fun startTracking() {
        permissionError = false
        if (previewSpec != null && appContainer.v1.previewStatus(previewSpec!!.getString("attemptId")) != PreviewRecordingStatus.READY) return
        ContextCompat.startForegroundService(this, Intent(this, V1GpsService::class.java).putExtra("taskId", intent.getStringExtra("taskId")).putExtra("previewAttemptId", previewSpec?.getString("attemptId")))
    }
}
