package com.michalkrsik.internetrestrictor.ui.challenge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.michalkrsik.internetrestrictor.BuildConfig
import com.michalkrsik.internetrestrictor.appContainer
import com.michalkrsik.internetrestrictor.domain.EXERCISE_SET_REWARD_MINUTES
import com.michalkrsik.internetrestrictor.domain.ExerciseId
import com.michalkrsik.internetrestrictor.domain.ExercisePolicies
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_PACKAGE_NAME
import com.michalkrsik.internetrestrictor.ui.components.ExercisePointsProgress
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.PulseActionBorder
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import com.michalkrsik.internetrestrictor.ui.theme.pulseButtonColors
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ExerciseChallengeActivity : ComponentActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var discardFinalize = false
    private var recordingStartedElapsedMs = 0L
    private val session by viewModels<ExerciseSession>()
    private var phase: ChallengePhase
        get() = session.phase
        set(value) { session.phase = value }
    private var status: String
        get() = session.status
        set(value) { session.status = value }
    private var recordingSecondsRemaining by mutableIntStateOf(
        ExerciseChallengeCatalog.RECORDING_LIMIT_SECONDS,
    )
    private var countdownSecondsRemaining by mutableIntStateOf(3)
    private var countdownDeadlineMs = 0L
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
            status = "Camera permission is required to earn more daily budget."
        }
    }

    private val recordingTicker = object : Runnable {
        override fun run() {
            if (phase != ChallengePhase.RECORDING) return
            val elapsedMs = SystemClock.elapsedRealtime() - recordingStartedElapsedMs
            recordingSecondsRemaining = (
                (maxRecordingMs - elapsedMs).coerceAtLeast(0) + 999L
            ).div(1_000L).toInt()
            handler.postDelayed(this, 500L)
        }
    }

    private val countdownTicker = object : Runnable {
        override fun run() {
            if (phase != ChallengePhase.COUNTDOWN) return
            val remaining = countdownDeadlineMs - SystemClock.elapsedRealtime()
            if (remaining <= 0) {
                startRecording()
            } else {
                countdownSecondsRemaining = ((remaining + 999) / 1_000).toInt()
                handler.postDelayed(this, minOf(remaining, 100L))
            }
        }
    }

    private fun startCountdown() {
        if (videoCapture == null || phase !in setOf(ChallengePhase.READY, ChallengePhase.RETRY)) return
        phase = ChallengePhase.COUNTDOWN
        countdownSecondsRemaining = 3
        countdownDeadlineMs = SystemClock.elapsedRealtime() + 3_000L
        handler.post(countdownTicker)
    }

    private val recordingTimeout = Runnable {
        if (phase == ChallengePhase.RECORDING) {
            stopRecording("Maximum recording time reached. Checking video…")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        recordingSecondsRemaining = ExerciseChallengeCatalog.RECORDING_LIMIT_SECONDS
        WindowCompat.setDecorFitsSystemWindows(window, false)
        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        setContent {
            androidx.activity.compose.BackHandler(enabled = phase in setOf(ChallengePhase.FINALIZING, ChallengePhase.CHECKING)) {}
            LaunchedEffect(phase) {
                if (phase == ChallengePhase.RESULT) {
                    delay(RESULT_DISPLAY_MS)
                    returnToOrigin()
                }
            }
            val snapshot by appContainer.repository.snapshot.collectAsState()
            RestrictorTheme(snapshot.settings.theme) {
                ExerciseChallengeScreen()
            }
        }
        if (session.pending == null && phase !in setOf(ChallengePhase.FINALIZING, ChallengePhase.CHECKING, ChallengePhase.RESULT)) {
            ensureCameraPermission()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        ExerciseChallengeVisibility.onActivityStarted()
    }

    override fun onStop() {
        handler.removeCallbacks(countdownTicker)
        if (phase == ChallengePhase.COUNTDOWN) {
            phase = ChallengePhase.READY
            status = "Ready when you are."
        }
        ExerciseChallengeVisibility.onActivityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(countdownTicker)
        handler.removeCallbacks(recordingTicker)
        handler.removeCallbacks(recordingTimeout)
        discardFinalize = true
        // Finalizing still belongs to this camera instance; verification belongs to the retained session.
        if (phase in setOf(ChallengePhase.RECORDING, ChallengePhase.COUNTDOWN, ChallengePhase.FINALIZING)) {
            phase = ChallengePhase.READY
            status = "Recording interrupted. Ready to record again."
        }
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
    private fun ExerciseChallengeScreen() {
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
                text = ExerciseChallengeCatalog.REWARD_COPY,
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
                    text = ExerciseChallengeCatalog.REWARD_COPY,
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
                enabled = phase !in setOf(ChallengePhase.FINALIZING, ChallengePhase.CHECKING),
                shape = PillShape,
                border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) {
                Text("Cancel")
            }
            OutlinedButton(
                onClick = ::flipCamera,
                enabled = hasFrontCamera && hasBackCamera &&
                    phase !in setOf(
                        ChallengePhase.COUNTDOWN,
                        ChallengePhase.RECORDING,
                        ChallengePhase.FINALIZING,
                        ChallengePhase.CHECKING,
                        ChallengePhase.RESULT,
                    ),
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
        val snapshot by appContainer.repository.snapshot.collectAsState()
        ExercisePointsProgress(snapshot.exercisePointSixthsToday)
        Text(
            text = ExerciseChallengeCatalog.CAMERA_INSTRUCTION,
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
            text = if (phase == ChallengePhase.COUNTDOWN) {
                "Get ready · $countdownSecondsRemaining"
            } else if (phase == ChallengePhase.RECORDING) {
                "Recording · ${recordingSecondsRemaining}s remaining"
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
            colors = pulseButtonColors(),
            border = PulseActionBorder,
            onClick = ::primaryAction,
            enabled = phase in setOf(
                ChallengePhase.READY,
                ChallengePhase.RECORDING,
                ChallengePhase.RETRY,
            ),
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
            shape = PillShape,
        ) {
            Text(
                when (phase) {
                    ChallengePhase.COUNTDOWN -> "Starting in $countdownSecondsRemaining…"
                    ChallengePhase.READY -> "Start exercise"
                    ChallengePhase.RECORDING -> "Finish recording"
                    ChallengePhase.FINALIZING -> "Finishing recording…"
                    ChallengePhase.CHECKING -> "Checking video…"
                    ChallengePhase.RETRY -> if (session.pending != null) "Retry saving" else "Record again"
                    ChallengePhase.RESULT -> "Returning to browser…"
                    ChallengePhase.PREPARING -> "Preparing camera…"
                    ChallengePhase.ERROR -> "Camera unavailable"
                },
            )
        }
    }

    private fun primaryAction() {
        when (phase) {
            ChallengePhase.READY, ChallengePhase.RETRY -> {
                if (session.pending != null) session.retrySave(appContainer, intent.getStringExtra(EXTRA_RETURN_PACKAGE) == YOUTUBE_PACKAGE_NAME)
                else if (videoCapture == null) ensureCameraPermission()
                else startCountdown()
            }
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
        if (phase !in setOf(ChallengePhase.FINALIZING, ChallengePhase.CHECKING, ChallengePhase.RESULT, ChallengePhase.RETRY)) {
            phase = ChallengePhase.PREPARING
            status = "Preparing camera…"
        }
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (isDestroyed) return@addListener
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
                if (session.pending == null && phase !in setOf(ChallengePhase.FINALIZING, ChallengePhase.CHECKING, ChallengePhase.RESULT)) {
                    phase = ChallengePhase.ERROR
                    status = "Could not open camera: ${error.message.orEmpty()}"
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera(provider: ProcessCameraProvider) {
        val preserveStatus = phase in setOf(ChallengePhase.FINALIZING, ChallengePhase.CHECKING, ChallengePhase.RESULT, ChallengePhase.RETRY)
        if (!preserveStatus) {
            phase = ChallengePhase.PREPARING
            status = "Switching camera…"
        }
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
        if (!preserveStatus) {
            phase = ChallengePhase.READY
            status = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                "Front camera ready."
            } else {
                "Back camera ready."
            }
        }
    }

    private fun flipCamera() {
        if (phase in setOf(ChallengePhase.COUNTDOWN, ChallengePhase.RECORDING, ChallengePhase.FINALIZING, ChallengePhase.CHECKING)) return
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
        if (BuildConfig.VERIFY_URL.isBlank() || BuildConfig.VERIFY_SHARED_SECRET.isBlank()) {
            phase = ChallengePhase.ERROR
            status = "Exercise verification is not configured."
            return
        }

        discardFinalize = false
        recordingSecondsRemaining = ExerciseChallengeCatalog.RECORDING_LIMIT_SECONDS
        recordingStartedElapsedMs = SystemClock.elapsedRealtime()
        phase = ChallengePhase.RECORDING
        status = "Recording…"
        val outputFile = File(cacheDir, "exercise_challenge_${System.currentTimeMillis()}.mp4")
        val verificationId = UUID.randomUUID().toString()
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
                    checkVideo(outputFile, verificationId)
                }
            }
        handler.post(recordingTicker)
        handler.postDelayed(recordingTimeout, maxRecordingMs)
    }

    private fun stopRecording(message: String) {
        if (phase != ChallengePhase.RECORDING) return
        phase = ChallengePhase.FINALIZING
        status = message
        handler.removeCallbacks(recordingTicker)
        handler.removeCallbacks(recordingTimeout)
        activeRecording?.stop()
    }

    private fun checkVideo(videoFile: File, verificationId: String) = session.checkVideo(
        videoFile, verificationId, appContainer,
        intent.getStringExtra(EXTRA_RETURN_PACKAGE) == YOUTUBE_PACKAGE_NAME,
    )

    private fun showRetry(message: String) {
        phase = ChallengePhase.RETRY
        status = message
    }

    private fun returnToOrigin() {
        if (openChallengeOrigin()) finish()
        else showRetry("The originating app could not be opened.")
    }


    companion object {
        const val EXTRA_RETURN_PACKAGE = "return_package"
        private const val TAG = "ExerciseChallenge"
        const val MODEL = "google/gemini-3.8-flash"
        private const val RESULT_DISPLAY_MS = 2_000L
    }

    private val maxRecordingMs: Long
        get() = ExerciseChallengeCatalog.RECORDING_LIMIT_SECONDS * 1_000L
}

internal fun formatExerciseResult(
    exerciseId: ExerciseId,
    measuredValue: Int,
    completedSets: Long,
): String {
    val policy = ExercisePolicies.find(exerciseId)
    val detected = "${policy?.resultLabel ?: "Exercise"} detected"
    val measurementText = if (exerciseId == ExerciseId.PLANK) {
        "$measuredValue ${if (measuredValue == 1) "second" else "seconds"}"
    } else {
        "$measuredValue ${if (measuredValue == 1) "repetition" else "repetitions"}"
    }
    return "$detected · $measurementText · +${completedSets * EXERCISE_SET_REWARD_MINUTES} min"
}

internal fun shouldReturnAfterExerciseResult(
    returningToYoutube: Boolean,
    youtubeUnlocked: Boolean,
    newlyEarnedRounds: Long,
): Boolean = shouldReturnAfterChallenge(returningToYoutube, youtubeUnlocked, newlyEarnedRounds)

internal object ExerciseChallengeVisibility : ChallengeVisibility()
