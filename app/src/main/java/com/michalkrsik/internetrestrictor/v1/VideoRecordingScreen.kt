package com.michalkrsik.internetrestrictor.v1

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.domain.TaskDefinition

internal data class VideoScreenState(
    val task: TaskDefinition,
    val creditName: String,
    val preview: Boolean,
    val phase: String,
    val status: String,
    val seconds: Int,
    val canRetry: Boolean,
)

internal data class VideoScreenActions(
    val back: () -> Unit,
    val stop: () -> Unit,
    val finish: () -> Unit,
    val retryPreview: () -> Unit,
    val begin: () -> Unit,
    val switchCamera: () -> Unit,
    val submit: () -> Unit,
)

@Composable
internal fun VideoRecordingScreen(model: VideoScreenState, actions: VideoScreenActions, cameraPreview: @Composable (Modifier) -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = actions.back) { Text(if (model.preview) "‹ Back to review" else "‹ Back") }
            Text(model.task.name, style = MaterialTheme.typography.headlineMedium)
            Text(if (model.preview) "Test recording · your setup and rewards stay unchanged" else "${model.task.unitsPerCredit} ${model.task.metric} = 1 ${model.creditName.removeSuffix("s")}", style = MaterialTheme.typography.bodyMedium)
            Text(model.task.instructions, maxLines = 4, style = MaterialTheme.typography.bodySmall)
            cameraPreview(Modifier.fillMaxWidth().weight(1f))
            Text(model.status)
            VideoRecordingControls(model, actions)
        }
    }
}

@Composable
private fun VideoRecordingControls(model: VideoScreenState, actions: VideoScreenActions) {
    if (model.phase in setOf("recording", "busy", "countdown")) {
        ActiveVideoControls(model, actions)
    } else {
        VideoReviewControls(model, actions)
    }
}

@Composable
private fun ActiveVideoControls(model: VideoScreenState, actions: VideoScreenActions) {
    if (model.phase == "recording") {
        Button(onClick = actions.stop, modifier = Modifier.fillMaxWidth()) { Text("Stop & verify · ${model.seconds}s left") }
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (model.phase == "countdown") "Starting in ${model.seconds}…" else "Checking your activity…")
    }
}

@Composable
private fun VideoReviewControls(model: VideoScreenState, actions: VideoScreenActions) {
    when (model.phase) {
        "result" -> Button(onClick = actions.finish, modifier = Modifier.fillMaxWidth()) { Text(if (model.preview) "Back to review" else "Back to your day") }
        "preview_waiting" -> {
            Button(onClick = actions.retryPreview, modifier = Modifier.fillMaxWidth()) { Text("Retry saved recording") }
            OutlinedButton(onClick = actions.finish, modifier = Modifier.fillMaxWidth()) { Text("Back to review") }
        }
        "preview_interrupted" -> Button(onClick = actions.finish, modifier = Modifier.fillMaxWidth()) { Text("Back to review") }
        else -> ReadyVideoControls(model.canRetry, actions)
    }
}

@Composable
private fun ReadyVideoControls(canRetry: Boolean, actions: VideoScreenActions) {
    Button(onClick = actions.begin, modifier = Modifier.fillMaxWidth()) { Text("Record · up to 30 seconds") }
    OutlinedButton(onClick = actions.switchCamera, modifier = Modifier.fillMaxWidth()) { Text("Switch camera") }
    if (canRetry) TextButton(onClick = actions.submit) { Text("Retry saved recording") }
}
