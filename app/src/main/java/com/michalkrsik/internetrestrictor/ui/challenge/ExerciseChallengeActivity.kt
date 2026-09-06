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
import com.michalkrsik.internetrestrictor.domain.EXERCISE_SET_REWARD_MINUTES
import com.michalkrsik.internetrestrictor.domain.ExerciseId
import com.michalkrsik.internetrestrictor.domain.ExercisePolicies
import com.michalkrsik.internetrestrictor.domain.SUPPORTED_BROWSERS
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_PACKAGE_NAME
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_UNLOCK_SETS
import com.michalkrsik.internetrestrictor.domain.accumulateExerciseUnits
import com.michalkrsik.internetrestrictor.domain.isProtectedTargetPackage
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExerciseChallengeActivity : ComponentActivity() {
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
    private var recordingSecondsRemaining by mutableIntStateOf(
        ExerciseChallengeCatalog.RECORDING_LIMIT_SECONDS,
    )
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

    private val recordingTimeout = Runnable {
        if (phase == ChallengePhase.RECORDING) {
            stopRecording("Maximum recording time reached. Checking video…")
        }
    }

    private val resultReturn = Runnable {
        if (phase == ChallengePhase.RESULT) returnToOrigin()
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
            val snapshot by appContainer.repository.snapshot.collectAsState()
            RestrictorTheme(snapshot.settings.theme) {
                ExerciseChallengeScreen()
            }
        }
        ensureCameraPermission()
    }

    override fun onStart() {
        super.onStart()
        ExerciseChallengeVisibility.onActivityStarted()
    }

    override fun onStop() {
        ExerciseChallengeVisibility.onActivityStopped()
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacks(recordingTicker)
        handler.removeCallbacks(recordingTimeout)
        handler.removeCallbacks(resultReturn)
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
            Text(
                text = ExerciseChallengeCatalog.EQUIVALENCE_COPY,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                Text(
                    text = ExerciseChallengeCatalog.EQUIVALENCE_COPY,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    phase !in setOf(
                        ChallengePhase.RECORDING,
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
            text = if (phase == ChallengePhase.RECORDING) {
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
                    ChallengePhase.READY -> "Start exercise"
                    ChallengePhase.RECORDING -> "Finish recording"
                    ChallengePhase.CHECKING -> "Checking video…"
                    ChallengePhase.RETRY -> "Record again"
                    ChallengePhase.RESULT -> "Returning to browser…"
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
        phase = ChallengePhase.CHECKING
        status = message
        handler.removeCallbacks(recordingTicker)
        handler.removeCallbacks(recordingTimeout)
        activeRecording?.stop()
    }

    private fun checkVideo(videoFile: File, verificationId: String) {
        phase = ChallengePhase.CHECKING
        status = "Checking exercise…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    runCatching {
                        callVerificationServer(
                            videoFile = videoFile,
                            verifyUrl = BuildConfig.VERIFY_URL,
                            sharedSecret = BuildConfig.VERIFY_SHARED_SECRET,
                        )
                    }
                } finally {
                    videoFile.delete()
                }
            }
            result.fold(
                onSuccess = onSuccess@{ verdict ->
                    val policy = ExercisePolicies.find(verdict.exerciseId)
                    if (verdict.exerciseId == ExerciseId.UNSUPPORTED || policy == null) {
                        appContainer.eventLog.record(
                            "Exercise video was unsupported: ${verdict.reason}",
                        )
                        showRetry("No supported exercise detected: ${verdict.reason}")
                        return@onSuccess
                    }
                    if (verdict.value <= 0) {
                        appContainer.eventLog.record(
                            "${policy.displayName} video had no countable ${policy.remainderNounPlural}",
                        )
                        showRetry("No ${policy.displayName.lowercase()} counted: ${verdict.reason}")
                        return@onSuccess
                    }
                    val previous = appContainer.repository.snapshot.value
                    val saved = appContainer.repository.recordVerifiedExercise(
                        verificationId,
                        verdict.exerciseId,
                        verdict.value,
                    )
                    if (!saved) {
                        showRetry("${policy.displayName} were counted, but the progress could not be saved.")
                        return@onSuccess
                    }
                    val updated = appContainer.repository.snapshot.value
                    val priorRemainder = previousRemainderFor(previous, updated, verdict.exerciseId)
                    val newlyEarnedRounds = accumulateExerciseUnits(
                        previousRemainder = priorRemainder,
                        added = verdict.value.toLong(),
                        unitsPerSet = policy.unitsPerSet,
                    ).completedSets
                    val earnedMinutes = newlyEarnedRounds * EXERCISE_SET_REWARD_MINUTES
                    val carried = updated.remainderFor(verdict.exerciseId)
                    appContainer.eventLog.record(
                        "${policy.displayName} video measured ${verdict.value}; " +
                            "$earnedMinutes daily budget minutes earned; " +
                            "$carried ${policy.remainderNounPlural} carried",
                    )
                    val baseResultMessage = formatExerciseResult(
                        exerciseId = verdict.exerciseId,
                        measuredValue = verdict.value,
                        completedSets = newlyEarnedRounds,
                        remainder = carried,
                    )
                    val returningToYoutube =
                        intent.getStringExtra(EXTRA_RETURN_PACKAGE) == YOUTUBE_PACKAGE_NAME
                    val youtubeUnlocked =
                        updated.earnedBudgetRoundsToday >= YOUTUBE_UNLOCK_SETS
                    val resultMessage = when {
                        returningToYoutube && youtubeUnlocked ->
                            "$baseResultMessage · YouTube unlocked until 04:00"
                        returningToYoutube ->
                            "$baseResultMessage · ${updated.earnedBudgetRoundsToday}/" +
                                "$YOUTUBE_UNLOCK_SETS toward YouTube"
                        else -> baseResultMessage
                    }
                    if (shouldReturnAfterExerciseResult(
                        returningToYoutube = returningToYoutube,
                        youtubeUnlocked = youtubeUnlocked,
                        newlyEarnedRounds = newlyEarnedRounds,
                    )) {
                        showResultAndReturn(resultMessage)
                    } else {
                        showRetry(resultMessage)
                    }
                },
                onFailure = { error ->
                    Log.e(TAG, "Exercise verification failed", error)
                    appContainer.eventLog.record(
                        "Exercise verification error: ${error.javaClass.simpleName}",
                    )
                    showRetry("Check failed: ${error.message.orEmpty()}")
                },
            )
        }
    }

    private fun showRetry(message: String) {
        handler.removeCallbacks(resultReturn)
        phase = ChallengePhase.RETRY
        status = message
    }

    private fun showResultAndReturn(message: String) {
        handler.removeCallbacks(resultReturn)
        phase = ChallengePhase.RESULT
        status = message
        handler.postDelayed(resultReturn, RESULT_DISPLAY_MS)
    }

    private fun callVerificationServer(
        videoFile: File,
        verifyUrl: String,
        sharedSecret: String,
    ): ExerciseVerificationResult {
        val boundary = "browser-restrictor-${System.currentTimeMillis()}"
        val videoPart = (
            "--$boundary\r\n" +
                "Content-Disposition: form-data; name=\"video\"; filename=\"challenge.mp4\"\r\n" +
                "Content-Type: video/mp4\r\n\r\n"
        ).toByteArray(Charsets.UTF_8)
        val suffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val contentLength = videoPart.size.toLong() + videoFile.length() + suffix.size
        val endpointUrl = verificationUrl(verifyUrl)
        val connection = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 30_000
            readTimeout = 200_000
            doOutput = true
            setFixedLengthStreamingMode(contentLength)
            setRequestProperty("Authorization", "Bearer $sharedSecret")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            connection.outputStream.buffered().use { output ->
                output.write(videoPart)
                videoFile.inputStream().buffered().use { input -> input.copyTo(output) }
                output.write(suffix)
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.let { BufferedReader(InputStreamReader(it)).use(BufferedReader::readText) }.orEmpty()
            if (code !in 200..299) throw IllegalStateException("Verification HTTP $code")
            return parseExerciseVerificationResult(body)
        } finally {
            connection.disconnect()
        }
    }

    private fun returnToOrigin() {
        val preferredPackage = intent.getStringExtra(EXTRA_RETURN_PACKAGE)
            ?.takeIf(::isProtectedTargetPackage)
        val candidatePackages = buildList {
            preferredPackage?.let(::add)
            SUPPORTED_BROWSERS.mapTo(this) { it.packageName }
        }.distinct()
        val launchIntent = candidatePackages.firstNotNullOfOrNull { packageName ->
            packageManager.getLaunchIntentForPackage(packageName)
        }
        if (launchIntent == null) {
            showRetry("The originating app could not be opened.")
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
        RESULT,
        RETRY,
        ERROR,
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
    remainder: Long,
): String {
    val policy = ExercisePolicies.find(exerciseId)
    val detected = "${policy?.resultLabel ?: "Exercise"} detected"
    val measurementText = if (exerciseId == ExerciseId.PLANK) {
        "$measuredValue ${if (measuredValue == 1) "second" else "seconds"}"
    } else {
        "$measuredValue ${if (measuredValue == 1) "repetition" else "repetitions"}"
    }
    val setWord = if (completedSets == 1L) "set" else "sets"
    val carriedNoun = policy?.let {
        if (remainder == 1L) it.remainderNounSingular else it.remainderNounPlural
    } ?: if (remainder == 1L) "unit" else "units"
    return "$detected · $measurementText · $completedSets $setWord earned · $remainder $carriedNoun carried"
}

internal fun shouldReturnAfterExerciseResult(
    returningToYoutube: Boolean,
    youtubeUnlocked: Boolean,
    newlyEarnedRounds: Long,
): Boolean = newlyEarnedRounds > 0 && (!returningToYoutube || youtubeUnlocked)

internal fun StateSnapshot.remainderFor(id: ExerciseId): Long = when (id) {
    ExerciseId.PUSH_UP -> pushupRepetitionRemainderToday
    ExerciseId.PLANK -> plankSecondRemainderToday
    ExerciseId.OUTDOOR_LAT_PULLDOWN -> machineRepetitionRemainderToday
    ExerciseId.SIT_UP, ExerciseId.UNSUPPORTED -> 0
}

private fun previousRemainderFor(
    previous: StateSnapshot,
    updated: StateSnapshot,
    exerciseId: ExerciseId,
): Long {
    val sameDay = previous.todayUsageReport.localDayKey == updated.todayUsageReport.localDayKey
    return if (sameDay) previous.remainderFor(exerciseId) else 0L
}

internal object ExerciseChallengeVisibility {
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
