package com.michalkrsik.internetrestrictor.ui

import android.app.Application
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import com.michalkrsik.internetrestrictor.domain.*
import com.michalkrsik.internetrestrictor.ui.sprint.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.time.Duration

/** Android services and sensors here are entirely Robolectric in-memory fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 35], application = Application::class)
class SprintLocationServiceTest {
    private val writes = mutableListOf<Triple<String, Long, Boolean>>()
    private var response: RewardSaveResult = RewardSaveResult.Saved(RewardCommit(1, 2, "2026-09-25"))
    private var pending: CompletableDeferred<RewardSaveResult>? = null
    private val service = SprintLocationService { id, meters, manual ->
        writes += Triple(id, meters, manual)
        pending?.await() ?: response
    }
    private val controller = ServiceController.of(service, Intent()).create()
    @After fun close() { controller.destroy() }
    private fun fix(meters: Double = 0.0, speed: Float? = 5f, accuracy: Float = 5f) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = 50.0 + meters / 111195.0; longitude = 14.0
        time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        this.accuracy = accuracy; if (speed != null) this.speed = speed
    }
    private fun tick(ms: Long = 1_000) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
    private fun recording() {
        service.startRecording(SPRINT_SET_QUALIFYING_METERS - 2)
        service.onLocationChanged(fix())
        for (n in 1..4) { tick(); service.onLocationChanged(fix(n * 5.0)) }
    }

    @Test fun readinessBinderAndResetPreserveOnlyTheRequestedRemainder() {
        assertSame(service, (service.onBind(null) as SprintLocationService.LocalBinder).service)
        assertEquals(SprintPhase.ACQUIRING_GPS, service.phase)
        service.saveReward()
        service.onLocationChanged(fix(accuracy = 26f, speed = null))
        assertFalse(service.liveState.value.isGpsUsable)
        assertEquals(0f, service.liveState.value.currentSpeedKmh)
        service.onLocationChanged(fix())
        assertTrue(service.liveState.value.isGpsUsable)
        assertEquals(18f, service.liveState.value.currentSpeedKmh)
        service.resetForAcquiringGps(99)
        assertEquals(99, service.liveState.value.totalDistanceWithRemainderMeters)
        service.onProviderDisabled("network")
        assertTrue(service.liveState.value.isGpsUsable)
        service.onProviderDisabled(LocationManager.GPS_PROVIDER)
        assertFalse(service.liveState.value.isGpsUsable)
        assertEquals(0, service.liveState.value.lastFixElapsedRealtimeMs)
        service.onProviderEnabled(LocationManager.GPS_PROVIDER)
        service.onStatusChanged(null, 0, null)
    }

    @Test fun qualifiedRecordingSavesOnceAndCannotResetDuringPersistence() {
        pending = CompletableDeferred()
        recording()
        service.startRecording(0)
        service.resetForAcquiringGps(0)
        assertTrue(service.isCurrentlyRecording())
        assertEquals(SprintPhase.RECORDING, service.phase)
        assertTrue(service.liveState.value.setsEarned > 0)
        val result = service.stopRecording()
        assertTrue(result.isGpsReliable)
        assertEquals(20, result.qualifyingDistanceMeters)
        assertEquals(SprintPhase.RESULT, service.phase)
        assertTrue(service.saveState!!.isSaving)
        service.resetForAcquiringGps(); service.saveReward()
        assertSame(result, service.stopRecording())
        assertEquals(1, writes.size)
        assertEquals(20, writes.single().second)
        assertFalse(writes.single().third)
        pending!!.complete(response); tick(1)
        assertTrue(service.saveState!!.saveSucceeded)
        assertEquals(1, service.saveState!!.newlyEarnedSets)
        assertEquals(2L, service.saveState!!.savedRemainder)
        service.saveReward(); assertEquals(1, writes.size)
        service.startRecording(0); assertFalse(service.isCurrentlyRecording())
        service.resetForAcquiringGps(2)
        assertNull(service.saveState)
        assertEquals(SprintPhase.ACQUIRING_GPS, service.phase)
    }

    @Test fun storageFailuresCanRetryButRejectedManualClaimsRemainUnawarded() {
        response = RewardSaveResult.StorageFailure
        recording(); service.stopRecording(); tick(1)
        assertTrue(service.saveState!!.canRetry)
        assertFalse(service.saveState!!.saveSucceeded)
        assertTrue(service.saveState!!.errorMessage!!.contains("Retry"))
        val id = writes.single().first
        response = RewardSaveResult.Rejected("Already corrected")
        service.saveReward(manual = true); tick(1)
        assertEquals(Triple(id, 0L, true), writes.last())
        assertFalse(service.saveState!!.canRetry)
        assertEquals("Already corrected", service.saveState!!.errorMessage)
        response = RewardSaveResult.Saved(RewardCommit(0, 50, "today"))
        service.saveReward(); tick(1)
        assertTrue(writes.last().third)
        assertTrue(service.saveState!!.saveSucceeded)
    }

    @Test fun timeoutStopsAnUnreliableRecordingAndReleasesResourcesWithoutAwarding() {
        service.startRecording(0)
        tick(SprintLocationService.MAX_RECORDING_MS)
        assertFalse(service.isCurrentlyRecording())
        assertEquals(SprintPhase.RESULT, service.phase)
        assertFalse(service.saveState!!.result.isGpsReliable)
        assertTrue(writes.isEmpty())
        assertTrue(shadowOf(service).isForegroundStopped)
    }
}
