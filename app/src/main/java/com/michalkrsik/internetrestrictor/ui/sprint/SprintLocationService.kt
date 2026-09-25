package com.michalkrsik.internetrestrictor.ui.sprint

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.michalkrsik.internetrestrictor.RestrictorApplication
import com.michalkrsik.internetrestrictor.domain.RewardSaveResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.michalkrsik.internetrestrictor.R
import com.michalkrsik.internetrestrictor.domain.SPRINT_SET_QUALIFYING_METERS
import com.michalkrsik.internetrestrictor.domain.SprintEvaluationResult
import com.michalkrsik.internetrestrictor.domain.SprintLiveState
import com.michalkrsik.internetrestrictor.domain.SprintLocation
import com.michalkrsik.internetrestrictor.domain.SprintMovementJudge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SprintLocationService(
    private val saveRecording: (suspend (String, Long, Boolean) -> RewardSaveResult)? = null,
) : Service(), LocationListener {

    inner class LocalBinder : Binder() {
        val service: SprintLocationService get() = this@SprintLocationService
    }

    private val binder = LocalBinder()
    private var locationManager: LocationManager? = null
    private var powerManager: PowerManager? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var movementJudge: SprintMovementJudge = SprintMovementJudge(0L)
    private var isRecording by mutableStateOf(false)
    var saveState by mutableStateOf<SprintSaveState?>(null)
        private set
    private var recordingId = ""
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private val recordingTimeout = Runnable { if (isRecording) stopRecording() }
    val phase: SprintPhase get() = when {
        isRecording -> SprintPhase.RECORDING
        saveState != null -> SprintPhase.RESULT
        else -> SprintPhase.ACQUIRING_GPS
    }

    private var lastVibratedSetCount: Long = 0L

    private val _liveState = MutableStateFlow(SprintLiveState.INITIAL)
    val liveState: StateFlow<SprintLiveState> = _liveState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        startListeningLocation()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun startListeningLocation() {
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                500L,
                0f,
                this,
                mainLooper,
            )
        } catch (error: SecurityException) {
            Log.w(TAG, "Location permission missing", error)
        } catch (error: Exception) {
            Log.e(TAG, "Failed to request location updates", error)
        }
    }

    fun resetGpsReadiness() {
        _liveState.value = _liveState.value.copy(
            isGpsUsable = false,
            lastAccuracyMeters = 999f,
            currentSpeedKmh = 0f,
            lastFixElapsedRealtimeMs = 0L,
        )
    }

    fun resetForAcquiringGps(startingRemainderMeters: Long = 0L) {
        if (isRecording || saveState?.isSaving == true) return
        saveState = null
        _liveState.value = _liveState.value.copy(
            qualifyingDistanceMeters = 0L,
            totalDistanceWithRemainderMeters = startingRemainderMeters,
            currentSpeedKmh = 0f,
            isQualified = false,
            setsEarned = 0L,
        )
    }

    fun startRecording(startingRemainderMeters: Long) {
        if (isRecording || saveState != null) return
        startService(Intent(this, SprintLocationService::class.java))
        recordingId = UUID.randomUUID().toString()
        isRecording = true
        handler.postDelayed(recordingTimeout, MAX_RECORDING_MS)
        lastVibratedSetCount = startingRemainderMeters / SPRINT_SET_QUALIFYING_METERS
        movementJudge = SprintMovementJudge(startingRemainderMeters)
        val nowElapsedMs = SystemClock.elapsedRealtime()
        movementJudge.onStart(nowElapsedMs)

        // Reset live counters immediately on start so old recording distance does not linger
        _liveState.value = SprintLiveState(
            qualifyingDistanceMeters = 0L,
            totalDistanceWithRemainderMeters = startingRemainderMeters,
            currentSpeedKmh = 0f,
            isQualified = (startingRemainderMeters >= SPRINT_SET_QUALIFYING_METERS),
            isGpsUsable = true,
            lastAccuracyMeters = _liveState.value.lastAccuracyMeters,
            setsEarned = startingRemainderMeters / SPRINT_SET_QUALIFYING_METERS,
            lastFixElapsedRealtimeMs = _liveState.value.lastFixElapsedRealtimeMs,
        )

        acquireWakeLock()
        startServiceForeground()
    }

    fun stopRecording(): SprintEvaluationResult {
        saveState?.let { return it.result }
        val result = movementJudge.onStop(SystemClock.elapsedRealtime())
        isRecording = false
        handler.removeCallbacks(recordingTimeout)
        lastVibratedSetCount = 0L
        releaseWakeLock()
        stopForegroundService()
        saveState = SprintSaveState(recordingId, result)
        if (result.isGpsReliable && result.qualifyingDistanceMeters > 0) saveReward(false)
        return result
    }

    fun saveReward(manual: Boolean = saveState?.manualConfirmed == true) {
        val pending = saveState ?: return
        if (pending.isSaving || pending.saveSucceeded) return
        saveState = pending.copy(isSaving = true, manualConfirmed = manual, errorMessage = null)
        saveScope.launch {
            val persist = saveRecording ?: (application as RestrictorApplication).container.repository::recordSprintRecording
            val saved = persist(
                pending.recordingId, if (manual) 0L else pending.result.qualifyingDistanceMeters, manual,
            )
            saveState = when (saved) {
                is RewardSaveResult.Saved -> saveState?.copy(
                    isSaving = false, saveSucceeded = true,
                    newlyEarnedSets = saved.commit.awardedSets,
                    savedRemainder = saved.commit.remainder,
                )
                RewardSaveResult.StorageFailure -> saveState?.copy(
                    isSaving = false, errorMessage = "Progress could not be saved. Retry saving to keep this sprint.",
                    canRetry = true,
                )
                is RewardSaveResult.Rejected -> saveState?.copy(
                    isSaving = false, errorMessage = saved.reason, canRetry = false,
                )
            }
        }
    }

    fun isCurrentlyRecording(): Boolean = isRecording

    override fun onLocationChanged(location: Location) {
        val fixElapsedMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.elapsedRealtimeNanos / 1_000_000L
        } else {
            SystemClock.elapsedRealtime()
        }

        val sprintLoc = SprintLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            timeWallMs = location.time,
            elapsedRealtimeMs = fixElapsedMs,
            accuracyMeters = location.accuracy,
            speedMps = if (location.hasSpeed()) location.speed else null,
        )

        val updatedState = if (isRecording) {
            val state = movementJudge.onLocation(sprintLoc)
            if (state.setsEarned > lastVibratedSetCount) {
                lastVibratedSetCount = state.setsEarned
                vibrateQualification()
            }
            state
        } else {
            val isUsable = location.accuracy <= SprintMovementJudge.GPS_USABLE_ACCURACY_THRESHOLD_METERS
            _liveState.value.copy(
                isGpsUsable = isUsable,
                lastAccuracyMeters = location.accuracy,
                currentSpeedKmh = if (location.hasSpeed()) location.speed * 3.6f else 0f,
                lastFixElapsedRealtimeMs = fixElapsedMs,
            )
        }

        _liveState.value = updatedState
    }

    private fun vibrateQualification() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            } ?: return

            if (!vibrator.hasVibrator()) return

            // VibrationEffect is available on every supported SDK (minSdk 29).
            vibrator.vibrate(VibrationEffect.createOneShot(500L, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (error: Exception) {
            Log.w(TAG, "Vibration failed", error)
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                wakeLock = powerManager?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "InternetRestrictor:SprintTrackingLock",
                )?.apply {
                    setReferenceCounted(false)
                }
            }
            wakeLock?.acquire(MAX_RECORDING_MS + 10_000L) // Covers automatic stop at the recording limit.
        } catch (error: Exception) {
            Log.w(TAG, "Failed to acquire wake lock", error)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (error: Exception) {
            Log.w(TAG, "Failed to release wake lock", error)
        }
    }

    private fun startServiceForeground() {
        val channelId = NOTIFICATION_CHANNEL_ID
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Sprint Tracking",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Tracks sprint distance and speed in the background"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(this, SprintChallengeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0),
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Sprint recording in progress")
            .setContentText("Tracking speed and distance. Screen can be locked.")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(recordingTimeout)
        saveScope.cancel()
        try {
            locationManager?.removeUpdates(this)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to remove location updates", error)
        }
        releaseWakeLock()
        stopForegroundService()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit

    override fun onProviderDisabled(provider: String) {
        if (provider == LocationManager.GPS_PROVIDER) {
            resetGpsReadiness()
        }
    }

    companion object {
        const val MAX_RECORDING_MS = 15 * 60 * 1000L
        private const val TAG = "SprintLocationService"
        private const val NOTIFICATION_ID = 2001
        private const val NOTIFICATION_CHANNEL_ID = "sprint_tracking_channel"
    }
}
