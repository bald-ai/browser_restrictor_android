package com.michalkrsik.internetrestrictor.v1

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.michalkrsik.internetrestrictor.appContainer
import com.michalkrsik.internetrestrictor.domain.TaskDefinition
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

class V1VideoActivity : ComponentActivity() {
    private lateinit var preview: PreviewView
    private var provider: ProcessCameraProvider? = null
    private var capture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var phase by mutableStateOf("ready")
    private var status by mutableStateOf("Position yourself so the activity is clearly visible.")
    private var seconds by mutableIntStateOf(30)
    private var task: TaskDefinition? = null
    private var front = true
    private var attempt: JSONObject? = null
    private var previewSpec: JSONObject? = null
    private var endedAt = 0L
    private var currentFile: File? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { stopRecording() }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status = "Enable camera access to record this task."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previewId = intent.getStringExtra("previewAttemptId")
        previewSpec = appContainer.v1.previewAttempt(previewId)
        if (previewId != null && previewSpec == null) {
            android.widget.Toast.makeText(this, "Test recording unavailable. Start a new test from the review card.", android.widget.Toast.LENGTH_LONG).show()
            finish(); return
        }
        task = previewSpec?.getJSONObject("task")?.let(com.michalkrsik.internetrestrictor.data.ConfigurationJson::parseTask)
            ?: appContainer.repository.snapshot.value.configuration.tasks.firstOrNull { it.id == intent.getStringExtra("taskId") && !it.paused && it.verifier == "video" }
        attempt = previewSpec
        if (task == null) { finish(); return }
        val recoveredPreview = refreshPreviewState()
        preview = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER }
        setContent {
            val snapshot by appContainer.repository.snapshot.collectAsState()
            RestrictorTheme(snapshot.settings.theme) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { if (phase == "recording") stopRecording() else finish() }) { Text(if (previewSpec != null) "‹ Back to review" else "‹ Back") }
                        Text(task!!.name, style = MaterialTheme.typography.headlineMedium)
                        Text(if (previewSpec != null) "Test recording · your setup and rewards stay unchanged" else "${task!!.unitsPerCredit} ${task!!.metric} = 1 ${snapshot.configuration.settings.creditName}", style = MaterialTheme.typography.bodyMedium)
                        Text(task!!.instructions, maxLines = 4, style = MaterialTheme.typography.bodySmall)
                        AndroidView(factory = { preview }, modifier = Modifier.fillMaxWidth().weight(1f))
                        Text(status)
                        when (phase) {
                            "recording" -> Button(onClick = ::stopRecording, modifier = Modifier.fillMaxWidth()) { Text("Stop & verify · ${seconds}s left") }
                            "busy", "countdown" -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (phase == "countdown") "Starting in $seconds…" else "Checking your activity…") }
                            "result" -> Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text(if (previewSpec != null) "Back to review" else "Back to your day") }
                            "preview_waiting" -> {
                                Button(onClick = ::retryPreview, modifier = Modifier.fillMaxWidth()) { Text("Retry saved recording") }
                                OutlinedButton(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Back to review") }
                            }
                            "preview_interrupted" -> Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth()) { Text("Back to review") }
                            else -> {
                                Button(onClick = ::begin, modifier = Modifier.fillMaxWidth()) { Text("Record · up to 30 seconds") }
                                OutlinedButton(onClick = { front = !front; startCamera() }, modifier = Modifier.fillMaxWidth()) { Text("Switch camera") }
                                if (currentFile?.exists() == true && attempt != null) TextButton(onClick = ::submit) { Text("Retry saved recording") }
                            }
                        }
                    }
                }
            }
        }
        if (!recoveredPreview) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
            else permission.launch(Manifest.permission.CAMERA)
        }
        if (previewSpec != null) lifecycleScope.launch {
            while (true) {
                if (phase in setOf("preview_waiting", "preview_interrupted")) refreshPreviewState()
                delay(1_000)
            }
        }
    }

    /** A preview attempt belongs to one recording, including after activity or process restart. */
    private fun refreshPreviewState(): Boolean {
        val id = previewSpec?.getString("attemptId") ?: return false
        when (appContainer.v1.previewStatus(id)) {
            PreviewRecordingStatus.READY -> return false
            PreviewRecordingStatus.COMPLETE -> {
                phase = "result"
                val result = appContainer.v1.previewResult(id)!!
                status = "Test result: ${result.opt("measuredUnits")} ${task?.metric.orEmpty()}. ${result.optString("reason")} No rewards granted."
            }
            PreviewRecordingStatus.WAITING_UPLOAD -> {
                phase = "preview_waiting"
                status = "Your test recording is saved on this phone. It will be checked when connected, or you can retry now."
            }
            else -> {
                phase = "preview_interrupted"
                status = "This test recording was interrupted. Return to the review card to start a new test."
            }
        }
        return true
    }

    private fun retryPreview() {
        val id = previewSpec?.getString("attemptId") ?: return
        phase = "busy"; status = "Checking your saved test recording…"
        appContainer.applicationScope.launch {
            try { appContainer.v1.retryPreview(id) }
            catch (_: Exception) { /* Evidence remains saved; recovery UI offers the same upload. */ }
            runOnUiThread { refreshPreviewState() }
        }
    }

    private fun startCamera() {
        if (phase == "recording") return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val cameraProvider = future.get(); provider = cameraProvider
                val preferred = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val selector = if (cameraProvider.hasCamera(preferred)) preferred else CameraSelector.DEFAULT_BACK_CAMERA
                val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                capture = VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD)).build())
                cameraProvider.unbindAll(); cameraProvider.bindToLifecycle(this, selector, previewUse, capture)
            } catch (error: Exception) { status = "Camera unavailable: ${error.message}" }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun begin() {
        if (refreshPreviewState()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.CAMERA)
            return
        }
        if (capture == null || phase !in setOf("ready", "error")) return
        phase = "busy"
        lifecycleScope.launch {
            try {
                attempt = if (previewSpec != null) {
                    appContainer.v1.claimPreviewRecording(previewSpec!!.getString("attemptId"))
                } else appContainer.v1.newAttempt(task!!, allowOffline = false)
                endedAt = 0L
                phase = "countdown"
                for (value in 3 downTo 1) { seconds = value; delay(1_000); if (phase != "countdown") return@launch }
                val file = File(filesDir, "v1-video-${attempt!!.getString("attemptId")}.mp4"); currentFile = file
                phase = "recording"; seconds = 30; status = "Recording. Keep the activity in view."
                val start = SystemClock.elapsedRealtime()
                recording = capture!!.output.prepareRecording(this@V1VideoActivity, FileOutputOptions.Builder(file).build())
                    .start(ContextCompat.getMainExecutor(this@V1VideoActivity)) { event ->
                        if (event is VideoRecordEvent.Finalize) {
                            recording = null; handler.removeCallbacks(timeout)
                            if (event.hasError() && file.length() == 0L) { phase = "error"; status = "Recording failed. Try again."; refreshPreviewState() }
                            else { if (endedAt == 0L) endedAt = System.currentTimeMillis(); submit() }
                        }
                    }
                handler.postDelayed(timeout, 30_000)
                while (phase == "recording") { seconds = (30 - (SystemClock.elapsedRealtime() - start) / 1_000).toInt().coerceAtLeast(0); delay(250) }
            } catch (error: Exception) { phase = "error"; status = error.message ?: "Connect to the internet to start a video."; refreshPreviewState() }
        }
    }

    private fun stopRecording() {
        if (phase != "recording") return
        endedAt = System.currentTimeMillis(); phase = "busy"; status = "Saving recording…"; recording?.stop()
    }

    private fun submit() {
        val pending = attempt ?: return; val file = currentFile ?: return
        phase = "busy"; status = "Your recording is being verified."
        // Application scope keeps the submitted evidence safe when the activity closes.
        appContainer.applicationScope.launch {
            try {
                val result = appContainer.v1.saveVideo(pending, file, endedAt)
                runOnUiThread { phase = "result"; status = if (result.optBoolean("preview")) "Test result: ${result.opt("measuredUnits")} ${task!!.metric}. ${result.optString("reason")} No rewards granted."
                    else "Verified ${result.opt("measuredUnits")} ${task!!.metric}. ${result.optString("reason")}" }
            } catch (error: Exception) {
                runOnUiThread { phase = "error"; status = "Recording saved for retry. ${error.message.orEmpty()}"; refreshPreviewState() }
            }
        }
    }

    override fun onStop() {
        if (phase == "recording") stopRecording()
        if (phase == "countdown") { phase = "ready"; status = "Recording interrupted. Ready when you are."; refreshPreviewState() }
        super.onStop()
    }
    override fun onDestroy() { handler.removeCallbacks(timeout); recording?.stop(); provider?.unbindAll(); super.onDestroy() }
}
