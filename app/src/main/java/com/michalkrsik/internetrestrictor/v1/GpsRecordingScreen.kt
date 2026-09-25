package com.michalkrsik.internetrestrictor.v1

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.domain.TaskDefinition
import org.json.JSONObject

internal data class GpsScreenState(
    val task: TaskDefinition?,
    val creditName: String,
    val gps: V1GpsState,
    val preview: Boolean = false,
    val previewStatus: PreviewRecordingStatus = PreviewRecordingStatus.READY,
    val previewResult: JSONObject? = null,
    val permissionError: Boolean = false,
    val canFallback: Boolean = false,
    val fallbackBusy: Boolean = false,
    val error: String? = null,
    val retrying: Boolean = false,
) {
    val recovered: Boolean get() = preview && !gps.recording && !gps.acquiring && !gps.submitting
    val displayedMeters: Long get() = if (recovered && previewStatus == PreviewRecordingStatus.COMPLETE)
        previewResult?.optDouble("measuredUnits")?.toLong() ?: 0 else gps.meters.toLong()
    val status: String get() = when {
        permissionError -> "Precise location permission is needed to verify distance."
        recovered -> recoveredStatus()
        else -> gps.status
    }
    private fun recoveredStatus(): String = when (previewStatus) {
        PreviewRecordingStatus.COMPLETE -> gpsResultStatus(previewResult)
        PreviewRecordingStatus.WAITING_UPLOAD -> "Your test recording is saved on this phone. It will be checked when connected, or you can retry now."
        PreviewRecordingStatus.INTERRUPTED, PreviewRecordingStatus.MISSING -> "This test recording was interrupted. Return to the review card to start a new test."
        PreviewRecordingStatus.READY -> gps.status
    }
}

internal data class GpsScreenActions(
    val back: () -> Unit,
    val start: () -> Unit,
    val stop: () -> Unit,
    val fallback: () -> Unit,
    val retry: () -> Unit,
)

/** Stateless recording presentation; permissions, location and uploads belong to the activity/service. */
@Composable
internal fun GpsRecordingScreen(state: GpsScreenState, actions: GpsScreenActions) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TextButton(onClick = actions.back) { Text(if (state.preview) "‹ Back to review" else "‹ Back to your day") }
            Text(state.task?.name ?: "GPS activity", style = MaterialTheme.typography.headlineLarge)
            Text(if (state.preview) "Test recording · your setup and rewards stay unchanged"
                else "${state.task?.unitsPerCredit ?: 0} meters = 1 ${state.creditName.removeSuffix("s")}")
            Spacer(Modifier.weight(1f))
            GpsMeasurement(state)
            GpsFallbackControls(state, actions.fallback)
            Text(if (state.task?.gpsMode == "walk") "Walk anywhere. Vehicle-speed sections, drift and tracking gaps do not count. Stop when you finish; the 4-hour cap ends a forgotten recording."
                else "Run outdoors. Qualifying sprint sections must reach 15 km/h. GPS is verified after you stop.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            GpsRecordingControls(state, actions)
        }
    }
}

@Composable
private fun GpsMeasurement(state: GpsScreenState) {
    val gps = state.gps
    Text("${state.displayedMeters} m", style = MaterialTheme.typography.displayLarge)
    Text("${gps.elapsedSeconds / 60}:${(gps.elapsedSeconds % 60).toString().padStart(2, '0')} elapsed · ${gps.accuracy?.toInt()?.let { "GPS ±${it}m" } ?: "Waiting for GPS"}")
    if (gps.acquiring || gps.submitting) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(state.status)
}

@Composable
private fun GpsFallbackControls(state: GpsScreenState, onFallback: () -> Unit) {
    if (state.canFallback) {
        Text("GPS coverage was insufficient. Confirm only if you completed this task.")
        OutlinedButton(enabled = !state.fallbackBusy, onClick = onFallback) {
            Text(if (state.fallbackBusy) "Saving…" else "I completed this task · 1 credit")
        }
    }
    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}

@Composable
private fun ColumnScope.GpsRecordingControls(state: GpsScreenState, actions: GpsScreenActions) {
    if (state.gps.recording || state.gps.acquiring) {
        Button(onClick = actions.stop, modifier = Modifier.fillMaxWidth()) { Text("Stop & verify") }
        Text("You can lock your screen. Recording keeps going.", modifier = Modifier.align(Alignment.CenterHorizontally), style = MaterialTheme.typography.bodySmall)
    } else if (state.recovered && state.previewStatus != PreviewRecordingStatus.READY) {
        RecoveredGpsControls(state.previewStatus, state.retrying, actions)
    } else if (!state.gps.submitting) {
        Button(onClick = actions.start, modifier = Modifier.fillMaxWidth()) { Text("Start recording") }
    }
}

@Composable
private fun RecoveredGpsControls(status: PreviewRecordingStatus, retrying: Boolean, actions: GpsScreenActions) {
    if (status == PreviewRecordingStatus.WAITING_UPLOAD) {
        Button(enabled = !retrying, onClick = actions.retry, modifier = Modifier.fillMaxWidth()) {
            Text(if (retrying) "Checking recording…" else "Retry saved recording")
        }
    }
    Button(onClick = actions.back, modifier = Modifier.fillMaxWidth()) { Text("Back to review") }
}
