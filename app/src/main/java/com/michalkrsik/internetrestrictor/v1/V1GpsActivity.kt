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
            val canFallback = canConfirmGpsFallback(previewSpec != null, gps, latest, snapshot.modifiableTaskReceiptIds)
            val screen = GpsScreenState(task, snapshot.configuration.settings.creditName, gps,
                preview = previewSpec != null, previewStatus = testStatus, previewResult = testResult,
                permissionError = permissionError, canFallback = canFallback,
                fallbackBusy = fallbackBusy, error = fallbackError, retrying = retryingTest)
            RestrictorTheme(snapshot.settings.theme) {
                GpsRecordingScreen(screen, GpsScreenActions(
                    back = { finish() },
                    start = ::requestTracking,
                    stop = { startService(Intent(this@V1GpsActivity, V1GpsService::class.java).setAction("stop")) },
                    fallback = {
                        fallbackBusy = true
                        scope.launch {
                            try { appContainer.v1.confirmGpsFallback(latest!!.measurement.attemptId) }
                            catch (error: Exception) { fallbackError = error.message }
                            finally { fallbackBusy = false }
                        }
                    },
                    retry = {
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
                    },
                ))
            }
        }
    }
    private fun requestTracking() {
        val requested = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        if (requested.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) startTracking()
        else permissions.launch(requested.toTypedArray())
    }

    private fun startTracking() {
        permissionError = false
        if (previewSpec != null && appContainer.v1.previewStatus(previewSpec!!.getString("attemptId")) != PreviewRecordingStatus.READY) return
        ContextCompat.startForegroundService(this, Intent(this, V1GpsService::class.java).putExtra("taskId", intent.getStringExtra("taskId")).putExtra("previewAttemptId", previewSpec?.getString("attemptId")))
    }
}

internal fun canConfirmGpsFallback(
    preview: Boolean, gps: V1GpsState,
    latest: com.michalkrsik.internetrestrictor.domain.SavedTaskReceipt?,
    modifiableIds: Set<String>,
): Boolean = !preview && !gps.recording && !gps.acquiring &&
    latest?.measurement?.fallbackEligible == true && !latest.measurement.manualCompletion &&
    latest.measurement.id in modifiableIds
