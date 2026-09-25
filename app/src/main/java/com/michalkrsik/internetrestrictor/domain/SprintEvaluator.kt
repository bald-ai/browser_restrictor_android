package com.michalkrsik.internetrestrictor.domain

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class SprintLocation(
    val latitude: Double,
    val longitude: Double,
    val timeWallMs: Long,
    val elapsedRealtimeMs: Long,
    val accuracyMeters: Float,
    val speedMps: Float? = null,
)

data class SprintEvaluationResult(
    val qualifyingDistanceMeters: Long,
    val averageSpeedKmh: Double,
    val setsEarned: Long,
    val newRemainderMeters: Long,
    val isGpsReliable: Boolean,
    val totalRecordedDistanceMeters: Double,
    val qualifyingEffortCount: Int,
)

data class SprintLiveState(
    val qualifyingDistanceMeters: Long = 0L,
    val totalDistanceWithRemainderMeters: Long = 0L,
    val currentSpeedKmh: Float = 0f,
    val isQualified: Boolean = false,
    val isGpsUsable: Boolean = false,
    val lastAccuracyMeters: Float = 999f,
    val setsEarned: Long = 0L,
    val lastFixElapsedRealtimeMs: Long = 0L,
) {
    companion object {
        val INITIAL = SprintLiveState()
    }
}

fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusMeters = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val rLat1 = Math.toRadians(lat1)
    val rLat2 = Math.toRadians(lat2)
    val a = sin(dLat / 2).pow(2) + cos(rLat1) * cos(rLat2) * sin(dLon / 2).pow(2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadiusMeters * c
}

class SprintMovementJudge(
    val startingRemainderMeters: Long = 0L,
) {
    companion object {
        const val GPS_USABLE_ACCURACY_THRESHOLD_METERS = 25.0f
        const val GPS_RECORDING_MAX_ACCURACY_METERS = 30.0f
        const val MAX_PLAUSIBLE_HUMAN_SPEED_MPS = 15.0 // 54 km/h

        // Effort detection thresholds
        const val RUNNING_ENTRY_SPEED_MPS = 3.6f // ~13.0 km/h: entering/staying in effort
        const val RUNNING_DIP_SPEED_MPS = 3.0f // ~10.8 km/h: speed dip floor
        const val MAX_SPEED_DIP_DURATION_MS = 3_500L // 3.5 seconds allowance for dips/noisy fixes
        const val MIN_EFFORT_QUALIFYING_SPEED_KMH = 14.95 // 15.0 km/h with float tolerance
        const val MAX_ACCEPTABLE_GPS_GAP_MS = 8_000L // Dropout threshold for reliability
        const val MAX_REJECTED_NOISY_FIX_RATIO = 0.60 // 60% rejected noisy fixes triggers unreliable GPS
    }

    internal data class EffortStep(
        val startLocation: SprintLocation,
        val endLocation: SprintLocation,
        val distanceMeters: Double,
        val reportedSpeedMps: Float?,
        val coordinateSpeedMps: Float,
    ) {
        val durationSec: Double
            get() = (endLocation.elapsedRealtimeMs - startLocation.elapsedRealtimeMs).coerceAtLeast(1L) / 1000.0

        val effectiveSpeedMps: Float
            get() = when {
                reportedSpeedMps != null && reportedSpeedMps >= 0f -> reportedSpeedMps
                else -> coordinateSpeedMps
            }
    }

    internal data class PrefixEval(
        val stepIndex: Int,
        val distanceMeters: Double,
        val durationSec: Double,
        val speedKmh: Double,
    )

    private val allValidLocations = mutableListOf<SprintLocation>()
    private var lastAcceptedLocation: SprintLocation? = null
    private var recordingStarted = false
    private var recordingStartTimeMs: Long = 0L
    private var totalRejectedNoisyFixes = 0
    private var totalRejectedJumps = 0
    private var maxGpsGapMs = 0L
    private var hasGrossDiscrepancy = false
    private var consecutiveDiscrepantSteps = 0

    private var completedQualifyingDistanceMeters = 0.0
    private var completedQualifyingDurationSeconds = 0.0
    private var completedQualifyingSpeedDuration = 0.0
    private var qualifyingEffortCount = 0
    private var totalRecordedDistanceMeters = 0.0

    // Active effort state
    private val activeEffortSteps = mutableListOf<EffortStep>()
    private var dipStartTimeMs: Long? = null

    fun onStart(startElapsedMs: Long) {
        recordingStarted = true
        recordingStartTimeMs = startElapsedMs
        lastAcceptedLocation = null
        allValidLocations.clear()
        totalRejectedNoisyFixes = 0
        totalRejectedJumps = 0
        maxGpsGapMs = 0L
        hasGrossDiscrepancy = false
        consecutiveDiscrepantSteps = 0
        completedQualifyingDistanceMeters = 0.0
        completedQualifyingDurationSeconds = 0.0
        completedQualifyingSpeedDuration = 0.0
        qualifyingEffortCount = 0
        totalRecordedDistanceMeters = 0.0
        activeEffortSteps.clear()
        dipStartTimeMs = null
    }

    fun onLocation(location: SprintLocation): SprintLiveState {
        val isUsable = location.accuracyMeters <= GPS_USABLE_ACCURACY_THRESHOLD_METERS

        // Filter out low-accuracy fixes during recording
        if (location.accuracyMeters > GPS_RECORDING_MAX_ACCURACY_METERS) {
            totalRejectedNoisyFixes++
            return currentLiveState(
                lastAccuracyMeters = location.accuracyMeters,
                isGpsUsable = isUsable,
                instantSpeedMps = 0f,
                fixElapsedRealtimeMs = location.elapsedRealtimeMs,
            )
        }

        if (location.elapsedRealtimeMs < recordingStartTimeMs) return currentLiveState(
            lastAccuracyMeters = location.accuracyMeters,
            isGpsUsable = false,
            instantSpeedMps = 0f,
            fixElapsedRealtimeMs = location.elapsedRealtimeMs,
        )
        val prev = lastAcceptedLocation
        if (prev != null) return onFollowingLocation(prev, location, isUsable)
        maxGpsGapMs = maxOf(maxGpsGapMs, location.elapsedRealtimeMs - recordingStartTimeMs)
        // First accepted location
        lastAcceptedLocation = location
        allValidLocations.add(location)
        return currentLiveState(
            lastAccuracyMeters = location.accuracyMeters,
            isGpsUsable = isUsable,
            instantSpeedMps = location.speedMps?.takeIf { it >= 0f } ?: 0f,
            fixElapsedRealtimeMs = location.elapsedRealtimeMs,
        )
    }

    private fun onFollowingLocation(prev: SprintLocation, location: SprintLocation, isUsable: Boolean): SprintLiveState {
        val dtMs = location.elapsedRealtimeMs - prev.elapsedRealtimeMs
        if (dtMs <= 50) {
            // Sub-50ms duplicate or near-duplicate fix; skip
            return currentLiveState(
                lastAccuracyMeters = location.accuracyMeters,
                isGpsUsable = isUsable,
                instantSpeedMps = prev.speedMps ?: 0f,
                fixElapsedRealtimeMs = location.elapsedRealtimeMs,
            )
        }
        if (dtMs > maxGpsGapMs) {
            maxGpsGapMs = dtMs
        }

        val dist = haversineDistanceMeters(
            prev.latitude, prev.longitude,
            location.latitude, location.longitude,
        )
        val impliedSpeed = dist / (dtMs / 1000.0)

        // Impossible jump / teleport filter
        if (impliedSpeed > MAX_PLAUSIBLE_HUMAN_SPEED_MPS) {
            totalRejectedJumps++
            return currentLiveState(
                lastAccuracyMeters = location.accuracyMeters,
                isGpsUsable = isUsable,
                instantSpeedMps = 0f,
                fixElapsedRealtimeMs = location.elapsedRealtimeMs,
            )
        }

        val reportedSpeed = location.speedMps?.takeIf { it >= 0f }
        trackSpeedDiscrepancy(reportedSpeed, impliedSpeed)

        val step = EffortStep(
            startLocation = prev,
            endLocation = location,
            distanceMeters = dist,
            reportedSpeedMps = reportedSpeed,
            coordinateSpeedMps = impliedSpeed.toFloat(),
        )

        totalRecordedDistanceMeters += dist
        processEffortStep(step)
        lastAcceptedLocation = location
        allValidLocations.add(location)

        return currentLiveState(
            lastAccuracyMeters = location.accuracyMeters,
            isGpsUsable = isUsable,
            instantSpeedMps = step.effectiveSpeedMps,
            fixElapsedRealtimeMs = location.elapsedRealtimeMs,
        )
    }

    private fun trackSpeedDiscrepancy(reportedSpeed: Float?, impliedSpeed: Double) {
        if (reportedSpeed != null && reportedSpeed >= RUNNING_ENTRY_SPEED_MPS && impliedSpeed < 1.0) {
            consecutiveDiscrepantSteps++
            if (consecutiveDiscrepantSteps >= 5) hasGrossDiscrepancy = true
        } else {
            consecutiveDiscrepantSteps = 0
        }
    }

    private fun processEffortStep(step: EffortStep) {
        if (step.effectiveSpeedMps >= RUNNING_ENTRY_SPEED_MPS) {
            activeEffortSteps.add(step)
            dipStartTimeMs = null
            return
        }
        if (activeEffortSteps.isEmpty()) return
        val dipStart = dipStartTimeMs ?: step.startLocation.elapsedRealtimeMs.also { dipStartTimeMs = it }
        if (step.endLocation.elapsedRealtimeMs - dipStart <= MAX_SPEED_DIP_DURATION_MS) {
            activeEffortSteps.add(step)
        } else {
            finalizeActiveEffort()
        }
    }

    private data class PrefixTotals(
        val distance: Double,
        val duration: Double,
        val weightedPace: Double,
        val missingSpeeds: Int,
    )

    private fun prefixTotals(steps: List<EffortStep>): PrefixTotals {
        var distance = 0.0
        var duration = 0.0
        var weightedPace = 0.0
        var missingSpeeds = 0
        for (step in steps) {
            distance += step.distanceMeters
            duration += step.durationSec
            if (step.reportedSpeedMps == null || !(step.reportedSpeedMps >= 0f)) missingSpeeds++
            weightedPace += step.effectiveSpeedMps * step.durationSec
        }
        return PrefixTotals(distance, duration, weightedPace, missingSpeeds)
    }

    private fun isStationaryJitter(steps: List<EffortStep>, totals: PrefixTotals): Boolean {
        if (totals.missingSpeeds <= steps.size / 2) return false
        val start = steps.first().startLocation
        val end = steps.last().endLocation
        val displacement = haversineDistanceMeters(start.latitude, start.longitude, end.latitude, end.longitude)
        val accuracy = steps.maxOf { it.endLocation.accuracyMeters }
        return displacement < accuracy || (totals.distance >= 15.0 && displacement / totals.distance < 0.35)
    }

    private fun findBestQualifyingPrefix(steps: List<EffortStep>): PrefixEval? {
        for (endIdx in steps.indices.reversed()) {
            if (endIdx < 1) continue // Two steps (three fixes) prevent a single spike earning credit.
            val prefix = steps.subList(0, endIdx + 1)
            val totals = prefixTotals(prefix)
            if (totals.duration < 1.5) continue
            if (isStationaryJitter(prefix, totals)) continue
            val speedKmh = totals.weightedPace / totals.duration * 3.6
            if (speedKmh >= MIN_EFFORT_QUALIFYING_SPEED_KMH && totals.distance >= 5.0) {
                return PrefixEval(endIdx, totals.distance, totals.duration, speedKmh)
            }
        }
        return null
    }

    private fun finalizeActiveEffort() {
        if (activeEffortSteps.isEmpty()) return

        val bestPrefix = findBestQualifyingPrefix(activeEffortSteps)
        if (bestPrefix != null) {
            completedQualifyingDistanceMeters += bestPrefix.distanceMeters
            completedQualifyingDurationSeconds += bestPrefix.durationSec
            completedQualifyingSpeedDuration += bestPrefix.speedKmh * bestPrefix.durationSec
            qualifyingEffortCount++
        }

        activeEffortSteps.clear()
        dipStartTimeMs = null
    }

    fun onStop(stopElapsedMs: Long): SprintEvaluationResult {
        if (recordingStarted) {
            val finalGap = stopElapsedMs - (lastAcceptedLocation?.elapsedRealtimeMs ?: recordingStartTimeMs)
            if (finalGap > maxGpsGapMs) {
                maxGpsGapMs = finalGap
            }
        }

        finalizeActiveEffort()

        val totalDurationMs = if (recordingStarted) {
            (stopElapsedMs - recordingStartTimeMs).coerceAtLeast(0L)
        } else 0L

        val isGpsReliable = evaluateReliability(totalDurationMs)

        val qualifyingDistanceLong = completedQualifyingDistanceMeters.roundToLong()
        val totalCombinedMeters = startingRemainderMeters + qualifyingDistanceLong
        val setsEarned = totalCombinedMeters / SPRINT_SET_QUALIFYING_METERS
        val newRemainder = totalCombinedMeters % SPRINT_SET_QUALIFYING_METERS

        val averageSpeedKmh = if (completedQualifyingDurationSeconds > 0.0) {
            completedQualifyingSpeedDuration / completedQualifyingDurationSeconds
        } else 0.0

        return SprintEvaluationResult(
            qualifyingDistanceMeters = qualifyingDistanceLong,
            averageSpeedKmh = averageSpeedKmh,
            setsEarned = setsEarned,
            newRemainderMeters = newRemainder,
            isGpsReliable = isGpsReliable,
            totalRecordedDistanceMeters = totalRecordedDistanceMeters,
            qualifyingEffortCount = qualifyingEffortCount,
        )
    }

    private fun evaluateReliability(totalDurationMs: Long): Boolean {
        if (totalDurationMs < 4_000L) {
            return true
        }
        if (allValidLocations.isEmpty()) {
            return false
        }
        if (maxGpsGapMs > MAX_ACCEPTABLE_GPS_GAP_MS) {
            return false
        }
        if (hasGrossDiscrepancy) {
            return false
        }
        val totalFixesAttempted = allValidLocations.size + totalRejectedNoisyFixes
        if (totalFixesAttempted >= 4 && (totalRejectedNoisyFixes.toDouble() / totalFixesAttempted) > MAX_REJECTED_NOISY_FIX_RATIO) {
            return false
        }
        return true
    }

    fun currentLiveState(
        lastAccuracyMeters: Float = lastAcceptedLocation?.accuracyMeters ?: 999f,
        isGpsUsable: Boolean = lastAccuracyMeters <= GPS_USABLE_ACCURACY_THRESHOLD_METERS,
        instantSpeedMps: Float = lastAcceptedLocation?.speedMps ?: 0f,
        fixElapsedRealtimeMs: Long = lastAcceptedLocation?.elapsedRealtimeMs ?: 0L,
    ): SprintLiveState {
        val activePrefix = findBestQualifyingPrefix(activeEffortSteps)
        val liveQualifyingDistance = completedQualifyingDistanceMeters + (activePrefix?.distanceMeters ?: 0.0)

        val qualifyingDistanceLong = liveQualifyingDistance.roundToLong()
        val totalCombined = startingRemainderMeters + qualifyingDistanceLong
        val setsEarned = totalCombined / SPRINT_SET_QUALIFYING_METERS
        val isQualified = setsEarned > 0

        return SprintLiveState(
            qualifyingDistanceMeters = qualifyingDistanceLong,
            totalDistanceWithRemainderMeters = totalCombined,
            currentSpeedKmh = (instantSpeedMps * 3.6f).coerceAtLeast(0f),
            isQualified = isQualified,
            isGpsUsable = isGpsUsable,
            lastAccuracyMeters = lastAccuracyMeters,
            setsEarned = setsEarned,
            lastFixElapsedRealtimeMs = fixElapsedRealtimeMs,
        )
    }
}
