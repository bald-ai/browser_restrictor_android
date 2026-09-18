package com.michalkrsik.internetrestrictor.v1

import android.app.*
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.michalkrsik.internetrestrictor.R
import com.michalkrsik.internetrestrictor.appContainer
import com.michalkrsik.internetrestrictor.domain.TaskDefinition
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class V1GpsState(val taskId: String = "", val recording: Boolean = false, val acquiring: Boolean = false,
    val meters: Double = 0.0, val elapsedSeconds: Long = 0, val accuracy: Float? = null,
    val status: String = "Ready to record. Keep your phone with you.", val submitting: Boolean = false,
    val preview: Boolean = false, val previewAttemptId: String? = null)

class V1GpsService : Service(), LocationListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: LocationManager
    private var task: TaskDefinition? = null
    private var attempt: JSONObject? = null
    private var points = JSONArray()
    private var previous: Location? = null
    private var meters = 0.0
    private var startElapsed = 0L
    private var lastPersistElapsed = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var ticker: Job? = null
    private var ending = false
    private var configRevision = -1L
    private var previewSpec: JSONObject? = null

    override fun onCreate() { super.onCreate(); manager = getSystemService(LocationManager::class.java) }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { finishRecording(); return START_NOT_STICKY }
        if (activeAttemptId != null || state.value.acquiring) return START_NOT_STICKY
        val previewId = intent?.getStringExtra("previewAttemptId")
        previewSpec = appContainer.v1.previewAttempt(previewId)
        if (previewId != null && previewSpec == null) { stopSelf(); return START_NOT_STICKY }
        task = previewSpec?.getJSONObject("task")?.let(com.michalkrsik.internetrestrictor.data.ConfigurationJson::parseTask)
            ?: appContainer.repository.snapshot.value.configuration.tasks.firstOrNull { it.id == intent?.getStringExtra("taskId") && !it.paused && it.verifier == "gps" }
        configRevision = previewSpec?.getLong("configRevision") ?: appContainer.repository.snapshot.value.appliedConfigRevision
        if (task == null || configRevision < 0) { state.value = V1GpsState(status = "Connect once to load your task setup before recording."); stopSelf(); return START_NOT_STICKY }
        if (previewId != null) {
            try { previewSpec = appContainer.v1.claimPreviewRecording(previewId) }
            catch (_: Exception) {
                state.value = V1GpsState(taskId = task!!.id, preview = true, previewAttemptId = previewId,
                    status = "This test already has a recording. Review its saved result or retry its upload.")
                stopSelf(); return START_NOT_STICKY
            }
        }
        ending = false
        startForegroundTracking()
        state.value = V1GpsState(taskId = task!!.id, acquiring = true, status = "Waiting for a clear GPS signal…", preview = previewSpec != null, previewAttemptId = previewSpec?.getString("attemptId"))
        scope.launch {
            try {
                appContainer.v1.api.bootstrap()
            } catch (_: Exception) { /* Saved identity/config enables offline GPS capture. */ }
            try {
                if (ContextCompat.checkSelfPermission(this@V1GpsService, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    state.value = state.value.copy(acquiring = false, status = "Precise location permission is required. Enable it and retry.")
                    stopSelf()
                    return@launch
                }
                manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, this@V1GpsService, mainLooper)
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Friction:V1GPS").apply { acquire(4 * 60 * 60_000L + 120_000) }
                ticker = launch {
                    val waitingSince = SystemClock.elapsedRealtime()
                    while (isActive) {
                        if (state.value.recording) {
                            val elapsed = (SystemClock.elapsedRealtime() - startElapsed) / 1_000
                            state.value = state.value.copy(elapsedSeconds = elapsed)
                            if (elapsed >= recordingLimitSeconds()) { finishRecording(); break }
                        } else if (SystemClock.elapsedRealtime() - waitingSince > 120_000) {
                            state.value = state.value.copy(acquiring = false, status = "No reliable GPS signal. Move outdoors and try again.")
                            finishRecording(); break
                        }
                        delay(1_000)
                    }
                }
            } catch (error: Exception) { state.value = state.value.copy(acquiring = false, status = "GPS unavailable: ${error.message}"); stopSelf() }
        }
        return START_NOT_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (ending) return
        val nowElapsed = SystemClock.elapsedRealtime()
        if (nowElapsed - location.elapsedRealtimeNanos / 1_000_000 !in 0..10_000) {
            previous = null
            return
        }
        val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
        state.value = state.value.copy(accuracy = location.accuracy)
        if (!state.value.recording) {
            if (location.accuracy > 25 || mock || !location.hasAccuracy()) return
            startElapsed = nowElapsed
            attempt = previewSpec?.let { JSONObject(it.toString()) } ?: JSONObject().put("attemptId", UUID.randomUUID().toString()).put("taskId", task!!.id)
                .put("startedAt", location.time).put("configRevision", configRevision)
            activeAttemptId = attempt!!.getString("attemptId")
            state.value = state.value.copy(acquiring = false, recording = true, status = "Recording · live distance is provisional")
            points = JSONArray(); meters = 0.0; previous = null
            persist(location.time, false)
        }
        val point = JSONObject().put("latitude", location.latitude).put("longitude", location.longitude)
            .put("time", location.time).put("accuracy", if (location.hasAccuracy()) location.accuracy.toDouble() else 999.0)
            .put("elapsedRealtime", location.elapsedRealtimeNanos / 1_000_000).put("isMock", mock)
        if (location.hasSpeed()) point.put("speed", location.speed.toDouble())
        points.put(point)
        val last = previous
        if (!mock && last != null && location.accuracy <= 25 && last.accuracy <= 25) {
            val seconds = (location.elapsedRealtimeNanos - last.elapsedRealtimeNanos) / 1_000_000_000.0
            val distance = last.distanceTo(location).toDouble()
            val speed = if (seconds > 0) distance / seconds else Double.POSITIVE_INFINITY
            val reported = if (location.hasSpeed()) location.speed.toDouble() else speed
            val threshold = if (task!!.gpsMode == "walk") 5.0 else 12.5
            val minimum = if (task!!.gpsMode == "sprint") 15 / 3.6 else 0.3
            if (seconds in 0.5..10.0 && speed in minimum..threshold && reported in minimum..threshold && distance >= maxOf(1.5, minOf(last.accuracy, location.accuracy) * 0.2)) meters += distance
        }
        previous = if (mock || location.accuracy > 25) null else location
        state.value = state.value.copy(meters = meters, status = if (location.accuracy > 25) "Weak GPS · poor readings do not count" else "Recording · live distance is provisional")
        if (nowElapsed - lastPersistElapsed >= 5_000) { persist(location.time, false); lastPersistElapsed = nowElapsed }
    }

    private fun persist(endedAt: Long, complete: Boolean) {
        attempt?.let { appContainer.v1.persistGps(it, points, endedAt, complete) }
    }

    private fun finishRecording() {
        if (ending) return
        ending = true; ticker?.cancel()
        runCatching { manager.removeUpdates(this) }
        wakeLock?.let { if (it.isHeld) it.release() }
        val id = activeAttemptId
        activeAttemptId = null
        state.value = state.value.copy(recording = false, acquiring = false)
        if (id == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        persist(System.currentTimeMillis(), true)
        state.value = state.value.copy(submitting = true, status = "Recording saved. Checking GPS evidence…")
        appContainer.applicationScope.launch {
            try {
                val result = appContainer.v1.finishGps(id)
                state.value = state.value.copy(submitting = false, status = if (result.optBoolean("preview")) "Test result: ${result.opt("measuredUnits")} meters. ${result.optString("reason")} No rewards granted."
                    else "Verified ${result.opt("measuredUnits")} meters. ${result.optString("reason")}")
            } catch (error: Exception) {
                state.value = state.value.copy(submitting = false, status = "Saved on your phone. It will verify when connected. ${error.message.orEmpty().take(90)}")
            }
        }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }

    private fun recordingLimitSeconds(): Long {
        val maximum = if (task?.gpsMode == "walk") 4 * 60 * 60L else 15 * 60L
        val beforeGps = previewSpec?.let { ((System.currentTimeMillis() - it.getLong("startedAt")) / 1_000 - state.value.elapsedSeconds).coerceAtLeast(0) } ?: 0
        return (maximum - beforeGps).coerceAtLeast(1)
    }
    private fun startForegroundTracking() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("v1-gps", "Activity recording", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 3101, Intent(this, V1GpsActivity::class.java).putExtra("taskId", task!!.id).putExtra("previewAttemptId", previewSpec?.getString("attemptId")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 3102, Intent(this, V1GpsService::class.java).setAction("stop"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "v1-gps").setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("${task!!.name} · GPS recording").setContentText("Keep your phone with you. Recording continues with screen off.")
            .setContentIntent(open).setOngoing(true).addAction(0, "Stop & verify", stop).build()
        startForeground(3101, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
    }

    override fun onDestroy() {
        if (!ending && attempt != null) {
            persist(points.optJSONObject(points.length() - 1)?.optLong("time") ?: System.currentTimeMillis(), true)
            state.value = state.value.copy(recording = false, acquiring = false, status = "Recording interrupted. Captured evidence saved for validation.")
        }
        activeAttemptId = null; runCatching { manager.removeUpdates(this) }
        wakeLock?.let { if (it.isHeld) it.release() }; scope.cancel(); super.onDestroy()
    }

    companion object {
        val state = MutableStateFlow(V1GpsState())
        @Volatile var activeAttemptId: String? = null
    }
}
