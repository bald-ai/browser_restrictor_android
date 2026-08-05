package com.michalkrsik.internetrestrictor.ui.challenge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.Surface
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.michalkrsik.internetrestrictor.BuildConfig
import com.michalkrsik.internetrestrictor.appContainer
import com.michalkrsik.internetrestrictor.domain.CHROME_PACKAGE
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class PushupChallengeActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var discardFinalize = false
    private var recordingStartedElapsedMs = 0L
    private var phase by mutableStateOf(ChallengePhase.PREPARING)
    private var status by mutableStateOf("Preparing camera…")
    private var elapsedSeconds by mutableIntStateOf(0)
    private var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_FRONT)
    private var hasFrontCamera by mutableStateOf(false)
    private var hasBackCamera by mutableStateOf(false)

    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            phase = ChallengePhase.ERROR
            status = "Camera permission is required."
        }
    }

    private val recordingTicker = object : Runnable {
        override fun run() {
            if (phase != ChallengePhase.RECORDING) return
            elapsedSeconds = (
                (SystemClock.elapsedRealtime() - recordingStartedElapsedMs) / 1_000L
            ).toInt()
            handler.postDelayed(this, 500L)
        }
    }

    private val recordingTimeout = Runnable {
        if (phase == ChallengePhase.RECORDING) {
            stopRecording("Time limit reached. Checking video…")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        setContent {
            val snapshot by appContainer.repository.snapshot.collectAsState()
            RestrictorTheme(snapshot.settings.theme) {
                PushupChallengeScreen()
            }
        }
        ensureCameraPermission()
    }

    override fun onStart() {
        super.onStart()
        PushupChallengeVisibility.onActivityStarted()
    }

    override fun onStop() {
        PushupChallengeVisibility.onActivityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(recordingTicker)
        handler.removeCallbacks(recordingTimeout)
        discardFinalize = true
        activeRecording?.stop()
        activeRecording = null
        cameraProvider?.unbindAll()
        cameraProvider = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        previewView.post {
            val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
            previewUseCase?.targetRotation = rotation
            videoCapture?.targetRotation = rotation
        }
    }

    @Composable
    private fun PushupChallengeScreen() {
        val isLandscape =
            LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
        ) {
            if (isLandscape) {
                LandscapeChallengeContent()
            } else {
                PortraitChallengeContent()
            }
        }
    }

    @Composable
    private fun PortraitChallengeContent() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ChallengeTopRow()
            Text(
                text = "5 push-ups for 30 minutes",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            InstructionText()
            CameraPreview(Modifier.fillMaxWidth().weight(1f))
            ChallengeStatus()
            PrimaryActionButton()
            Spacer(Modifier.height(2.dp))
        }
    }

    @Composable
    private fun LandscapeChallengeContent() {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CameraPreview(Modifier.weight(1.7f).fillMaxHeight())
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChallengeTopRow()
                Text(
                    text = "5 push-ups for 30 minutes",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                InstructionText()
                Spacer(Modifier.weight(1f))
                ChallengeStatus()
                PrimaryActionButton()
            }
        }
    }

    @Composable
    private fun ChallengeTopRow() {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            OutlinedButton(
                onClick = ::finish,
                shape = PillShape,
                border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text("Cancel")
            }
            OutlinedButton(
                onClick = ::flipCamera,
                enabled = hasFrontCamera && hasBackCamera &&
                    phase !in setOf(ChallengePhase.RECORDING, ChallengePhase.CHECKING),
                shape = PillShape,
                border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text(
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        "Flip to back"
                    } else {
                        "Flip to front"
                    },
                )
            }
        }
    }

    @Composable
    private fun InstructionText() {
        Text(
            text = "Show your upper body. Form and framing can be imperfect. " +
                "Tap Start, do 5 push-ups, then Finish.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }

    @Composable
    private fun CameraPreview(modifier: Modifier) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    @Composable
    private fun ChallengeStatus() {
        Text(
            text = if (phase == ChallengePhase.RECORDING) {
                "Recording ${elapsedSeconds}s · finish when done"
            } else {
                status
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (phase == ChallengePhase.ERROR) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
    }

    @Composable
    private fun PrimaryActionButton() {
        Button(
            onClick = ::primaryAction,
            enabled = phase in setOf(
                ChallengePhase.READY,
                ChallengePhase.RECORDING,
                ChallengePhase.RETRY,
            ),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = PillShape,
        ) {
            Text(
                when (phase) {
                    ChallengePhase.READY -> "Start recording"
                    ChallengePhase.RECORDING -> "Finish recording"
                    ChallengePhase.CHECKING -> "Checking video…"
                    ChallengePhase.RETRY -> "Record again"
                    ChallengePhase.PREPARING -> "Preparing camera…"
                    ChallengePhase.ERROR -> "Camera unavailable"
                },
            )
        }
    }

    private fun primaryAction() {
        when (phase) {
            ChallengePhase.READY, ChallengePhase.RETRY -> startRecording()
            ChallengePhase.RECORDING -> stopRecording("Finishing recording…")
            else -> Unit
        }
    }

    private fun ensureCameraPermission() {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        phase = ChallengePhase.PREPARING
        status = "Preparing camera…"
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            runCatching {
                val provider = providerFuture.get()
                cameraProvider = provider
                hasFrontCamera = provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                hasBackCamera = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                if (!hasFrontCamera && !hasBackCamera) {
                    throw IllegalStateException("No camera is available")
                }
                if (!hasFrontCamera) lensFacing = CameraSelector.LENS_FACING_BACK
                bindCamera(provider)
            }.onFailure { error ->
                Log.e(TAG, "Camera bind failed", error)
                phase = ChallengePhase.ERROR
                status = "Could not open camera: ${error.message.orEmpty()}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        phase = ChallengePhase.PREPARING
        status = "Switching camera…"
        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(Quality.SD))
            .build()
        val capture = VideoCapture.withOutput(recorder).also {
            it.targetRotation = rotation
        }
        val selector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()
        provider.unbindAll()
        provider.bindToLifecycle(this, selector, preview, capture)
        previewUseCase = preview
        videoCapture = capture
        phase = ChallengePhase.READY
        status = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            "Front camera ready."
        } else {
            "Back camera ready."
        }
    }

    private fun flipCamera() {
        if (phase in setOf(ChallengePhase.RECORDING, ChallengePhase.CHECKING)) return
        val provider = cameraProvider ?: return
        val target = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
            CameraSelector.LENS_FACING_BACK
        } else {
            CameraSelector.LENS_FACING_FRONT
        }
        val available = if (target == CameraSelector.LENS_FACING_FRONT) {
            hasFrontCamera
        } else {
            hasBackCamera
        }
        if (!available) return
        lensFacing = target
        runCatching { bindCamera(provider) }
            .onFailure { error ->
                Log.e(TAG, "Camera flip failed", error)
                phase = ChallengePhase.ERROR
                status = "Could not switch camera: ${error.message.orEmpty()}"
            }
    }

    private fun startRecording() {
        val capture = videoCapture ?: return
        if (BuildConfig.OPENROUTER_API_KEY.isBlank()) {
            phase = ChallengePhase.ERROR
            status = "OpenRouter is not configured."
            return
        }

        discardFinalize = false
        elapsedSeconds = 0
        recordingStartedElapsedMs = SystemClock.elapsedRealtime()
        phase = ChallengePhase.RECORDING
        status = "Recording…"
        val outputFile = File(cacheDir, "pushup_challenge_${System.currentTimeMillis()}.mp4")
        val outputOptions = FileOutputOptions.Builder(outputFile).build()
        activeRecording = capture.output
            .prepareRecording(this, outputOptions)
            .start(ContextCompat.getMainExecutor(this)) { event ->
                if (event !is VideoRecordEvent.Finalize) return@start
                activeRecording = null
                handler.removeCallbacks(recordingTicker)
                handler.removeCallbacks(recordingTimeout)
                if (discardFinalize) {
                    outputFile.delete()
                } else if (event.hasError()) {
                    outputFile.delete()
                    Log.e(TAG, "Recording failed: ${event.error}")
                    showRetry("Recording failed. Try again.")
                } else {
                    checkVideo(outputFile)
                }
            }
        handler.post(recordingTicker)
        handler.postDelayed(recordingTimeout, MAX_RECORDING_MS)
    }

    private fun stopRecording(message: String) {
        if (phase != ChallengePhase.RECORDING) return
        phase = ChallengePhase.CHECKING
        status = message
        handler.removeCallbacks(recordingTicker)
        handler.removeCallbacks(recordingTimeout)
        activeRecording?.stop()
    }

    private fun checkVideo(videoFile: File) {
        phase = ChallengePhase.CHECKING
        status = "Checking your push-ups…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { callOpenRouter(videoFile, BuildConfig.OPENROUTER_API_KEY) }
            }
            videoFile.delete()
            result.fold(
                onSuccess = { verdict ->
                    if (verdict.repetitionCount >= REQUIRED_PUSHUPS) {
                        val granted = appContainer.repository.grantPushupUnlock()
                        if (granted) {
                            appContainer.eventLog.record(
                                "Push-up challenge passed with ${verdict.repetitionCount} reps",
                            )
                            returnToChrome()
                        } else {
                            showRetry("Push-ups passed, but the unlock could not be saved.")
                        }
                    } else {
                        appContainer.eventLog.record(
                            "Push-up challenge rejected with ${verdict.repetitionCount} reps",
                        )
                        showRetry(
                            "Not accepted: ${verdict.reason} " +
                                "(${verdict.repetitionCount}/$REQUIRED_PUSHUPS counted)",
                        )
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Push-up verification failed", error)
                    appContainer.eventLog.record(
                        "Push-up verification error: ${error.javaClass.simpleName}",
                    )
                    showRetry("Check failed: ${error.message.orEmpty()}")
                },
            )
        }
    }

    private fun showRetry(message: String) {
        phase = ChallengePhase.RETRY
        status = message
    }

    private fun callOpenRouter(videoFile: File, apiKey: String): PushupVerdict {
        val dataUrl = "data:video/mp4;base64," +
            Base64.encodeToString(videoFile.readBytes(), Base64.NO_WRAP)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", userPrompt()))
            .put(
                JSONObject()
                    .put("type", "video_url")
                    .put("video_url", JSONObject().put("url", dataUrl)),
            )
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt()))
            .put(JSONObject().put("role", "user").put("content", content))
        val payload = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("max_tokens", 300)

        val connection = (URL(OPENROUTER_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 180_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("HTTP-Referer", "https://github.com/bald-ai/browser_restrictor_android")
            setRequestProperty("X-Title", "browser-restrictor-pushup-challenge")
        }
        try {
            OutputStreamWriter(connection.outputStream).use { it.write(payload.toString()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = BufferedReader(InputStreamReader(stream)).use { it.readText() }
            if (code !in 200..299) throw IllegalStateException("OpenRouter HTTP $code: $body")
            val rawContent = JSONObject(body)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            val verdict = extractJson(rawContent)
            return PushupVerdict(
                isSuccess = verdict.optBoolean("is_success", false),
                confidence = verdict.optDouble("confidence", 0.0),
                repetitionCount = verdict.optInt("repetition_count", 0).coerceAtLeast(0),
                reason = verdict.optString("reason", "No reason returned"),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun systemPrompt(): String =
        "You verify a casual push-up video for a personal app unlock. Count generously. " +
            "The person's whole body does not need to be visible: repeated lowering and rising of the " +
            "visible head, shoulders, torso, or arms is enough to count push-up repetitions. " +
            "Accept imperfect camera angles, partial framing, ordinary form, incomplete depth, brief " +
            "occlusion, and motion that is recognizable as push-ups from its continuity. " +
            "When choosing between two plausible repetition counts, use the higher count. " +
            "Return zero only when no push-up-like repetitions are visible. " +
            "Do not count unrelated exercises, obvious cuts or loops, or a recording of a screen. " +
            "Set is_success to true whenever repetition_count is at least $REQUIRED_PUSHUPS."

    private fun userPrompt(): String =
        "Review this direct camera recording and count the valid push-ups. " +
            "Reply only with one JSON object and no markdown. " +
            "Schema: {\"is_success\": boolean, \"confidence\": number, " +
            "\"repetition_count\": integer, \"reason\": string}"

    private fun extractJson(text: String): JSONObject {
        val trimmed = text.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start == -1 || end <= start) {
            throw IllegalStateException("No JSON verdict in model response")
        }
        return JSONObject(trimmed.substring(start, end + 1))
    }

    private fun returnToChrome() {
        val launchIntent = packageManager.getLaunchIntentForPackage(CHROME_PACKAGE)
        if (launchIntent == null) {
            showRetry("Chrome could not be opened.")
            return
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(launchIntent)
        finish()
    }

    private enum class ChallengePhase {
        PREPARING,
        READY,
        RECORDING,
        CHECKING,
        RETRY,
        ERROR,
    }

    private data class PushupVerdict(
        val isSuccess: Boolean,
        val confidence: Double,
        val repetitionCount: Int,
        val reason: String,
    )

    private companion object {
        const val TAG = "PushupChallenge"
        const val OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
        const val MODEL = "google/gemini-3.5-flash-lite"
        const val REQUIRED_PUSHUPS = 5
        const val MAX_RECORDING_MS = 60_000L
    }
}

internal object PushupChallengeVisibility {
    val active = MutableStateFlow(false)
    val activityStarted = MutableStateFlow(false)

    fun prepareLaunch() {
        activityStarted.value = false
        active.value = true
    }

    fun onActivityStarted() {
        activityStarted.value = true
        active.value = true
    }

    fun onActivityStopped() {
        activityStarted.value = false
        active.value = false
    }

    fun cancelLaunch() {
        activityStarted.value = false
        active.value = false
    }
}
