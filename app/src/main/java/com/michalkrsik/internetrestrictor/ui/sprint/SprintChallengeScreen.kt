package com.michalkrsik.internetrestrictor.ui.sprint

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michalkrsik.internetrestrictor.domain.EXERCISE_SET_REWARD_MINUTES
import com.michalkrsik.internetrestrictor.domain.SPRINT_INTENDED_METERS
import com.michalkrsik.internetrestrictor.domain.SPRINT_SET_QUALIFYING_METERS
import com.michalkrsik.internetrestrictor.domain.SprintLiveState
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_PACKAGE_NAME
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_UNLOCK_SETS
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.PulseActionBorder
import com.michalkrsik.internetrestrictor.ui.theme.PulseText
import com.michalkrsik.internetrestrictor.ui.theme.PulseYellow
import com.michalkrsik.internetrestrictor.ui.theme.RestrictorTheme
import com.michalkrsik.internetrestrictor.ui.theme.pulseButtonColors
import java.util.Locale

internal data class SprintScreenState(
    val phase: SprintPhase,
    val saveState: SprintSaveState?,
    val hasFineLocation: Boolean,
    val gpsProviderEnabled: Boolean,
    val fixFresh: Boolean,
    val returningToYoutube: Boolean,
)

internal data class SprintScreenActions(
    val close: () -> Unit,
    val permissions: () -> Unit,
    val locationSettings: () -> Unit,
    val start: (Long) -> Unit,
    val stop: () -> Unit,
    val confirmManual: () -> Unit,
    val retry: () -> Unit,
    val continueAfterResult: (Boolean, Boolean, Long) -> Unit,
    val again: (Long) -> Unit,
)

private val PulseGreen = Color(0xFF4CAF50)
private val PulseRed = Color(0xFFE53935)

@Composable
internal fun SprintChallengeScreen(
    model: SprintScreenState,
    actions: SprintScreenActions,
    snapshotRemainder: Long,
    earnedSetsToday: Long,
    liveState: SprintLiveState,
) {
    val isSaving = model.saveState?.isSaving == true

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            TopBar(isSaving = isSaving, onClose = actions.close)

            when (model.phase) {
                SprintPhase.ACQUIRING_GPS -> PreStartContent(snapshotRemainder, liveState, model, actions)
                SprintPhase.RECORDING -> RecordingContent(liveState)
                SprintPhase.RESULT -> ResultContent(earnedSetsToday, model, actions)
            }

            BottomActionArea(model, actions, snapshotRemainder, liveState, earnedSetsToday)
        }
    }
}

@Composable
private fun TopBar(isSaving: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "SPRINT CHALLENGE",
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 1.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )
        TextButton(
            onClick = onClose,
            enabled = !isSaving,
        ) {
            Text(
                "Close",
                color = if (isSaving) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PreStartContent(snapshotRemainder: Long, liveState: SprintLiveState, model: SprintScreenState, actions: SprintScreenActions) {
    val fresh = model.fixFresh
    val usable = liveState.isGpsUsable && fresh

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "150m Sprint",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold),
            textAlign = TextAlign.Center,
        )
        Text(
            "Run at 15+ km/h. 120m earns 3 minutes & contributes toward YouTube unlock.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        if (snapshotRemainder > 0) {
            Box(
                modifier = Modifier
                    .background(PulseYellow.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    "${snapshotRemainder}m carried over from previous sprint",
                    color = PulseYellow,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        when {
            !model.hasFineLocation -> {
                Text(
                    "Precise location permission is required for GPS distance & speed measurement.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Button(
                    onClick = actions.permissions,
                    colors = pulseButtonColors(),
                    shape = PillShape,
                ) {
                    Text("Grant permission")
                }
            }
            !model.gpsProviderEnabled -> {
                Text(
                    "GPS is turned off in settings. Please turn on Location.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(
                    onClick = actions.locationSettings,
                    shape = PillShape,
                ) {
                    Text("Open Location Settings")
                }
            }
            !usable -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = PulseYellow,
                        strokeWidth = 2.dp,
                    )
                    val accText = if (liveState.lastAccuracyMeters < 900f) {
                        if (!fresh) "Stale fix, waiting for update…"
                        else "Accuracy: ${liveState.lastAccuracyMeters.toInt()}m (need ≤25m)"
                    } else {
                        "Waiting for satellite fix…"
                    }
                    Text(
                        "Acquiring precise GPS… $accText",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> {
                Text(
                    "GPS ready · Accuracy: ${liveState.lastAccuracyMeters.toInt()}m",
                    color = PulseGreen,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun RecordingContent(liveState: SprintLiveState) {
    val currentDist = liveState.totalDistanceWithRemainderMeters
    val progress = (currentDist.toFloat() / SPRINT_INTENDED_METERS).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (liveState.isQualified) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(PulseGreen.copy(alpha = 0.2f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    "Sprint complete — tap Stop",
                    color = PulseGreen,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
            }
        } else {
            Text(
                "Sprint in progress",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            "${currentDist}m",
            style = MaterialTheme.typography.displayLarge.copy(
                fontSize = 72.sp,
                fontWeight = FontWeight.Black,
            ),
        )
        Text(
            "of ${SPRINT_INTENDED_METERS}m target (${SPRINT_SET_QUALIFYING_METERS}m to qualify)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp)),
            color = if (liveState.isQualified) PulseGreen else PulseYellow,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                String.format(Locale.US, "%.1f km/h", liveState.currentSpeedKmh),
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = if (liveState.currentSpeedKmh >= 15f) PulseGreen else MaterialTheme.colorScheme.onSurface,
            )
        }

        PulseText("Screen can be locked while running", 10, muted = true)
    }
}

@Composable
private fun ResultContent(earnedSetsToday: Long, model: SprintScreenState, actions: SprintScreenActions) {
    val saveState = model.saveState
    val result = saveState?.result
    val returningToYoutube = model.returningToYoutube
    val youtubeUnlocked = earnedSetsToday >= YOUTUBE_UNLOCK_SETS

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "Sprint Results",
            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        )

        if (result != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                MetricBox(
                    label = "DISTANCE",
                    value = "${result.qualifyingDistanceMeters}m",
                )
                MetricBox(
                    label = "AVG SPEED",
                    value = String.format(Locale.US, "%.1f km/h", result.averageSpeedKmh),
                )
            }

            SprintRewardSummary(saveState, result, actions)

            if (saveState.errorMessage != null) {
                Text(
                    saveState.errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
            }

            if (returningToYoutube) {
                Text(
                    if (youtubeUnlocked) "YouTube unlocked until 04:00" else "$earnedSetsToday/$YOUTUBE_UNLOCK_SETS toward YouTube unlock",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SprintRewardSummary(saveState: SprintSaveState, result: com.michalkrsik.internetrestrictor.domain.SprintEvaluationResult, actions: SprintScreenActions) {
    if (saveState.newlyEarnedSets > 0) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PulseGreen.copy(alpha = 0.15f))
                .padding(16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "+${saveState.newlyEarnedSets} set${if (saveState.newlyEarnedSets > 1) "s" else ""} (+${saveState.newlyEarnedSets * EXERCISE_SET_REWARD_MINUTES} min)",
                    color = PulseGreen,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                )
                if ((saveState.savedRemainder ?: 0L) > 0 && !saveState.manualConfirmed) {
                    Text(
                        "${saveState.savedRemainder ?: result.newRemainderMeters}m carried toward next set",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    } else if (!result.isGpsReliable) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(PulseYellow.copy(alpha = 0.15f))
                .padding(14.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "GPS signal was unreliable during recording.",
                    color = PulseYellow,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                if (!saveState.manualConfirmed && !saveState.saveSucceeded) {
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = actions.confirmManual,
                        colors = pulseButtonColors(),
                        shape = PillShape,
                        enabled = !saveState.isSaving,
                    ) {
                        Text(if (saveState.isSaving) "Saving…" else "Confirm completed sprint (+1 set)")
                    }
                } else if (saveState.saveSucceeded) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "+1 set awarded",
                        color = PulseGreen,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    } else {
        Text(
            if (saveState.saveSucceeded) "${saveState.savedRemainder}m carried over. Need ${SPRINT_SET_QUALIFYING_METERS - (saveState.savedRemainder ?: 0L)}m more for next set."
            else "${result.qualifyingDistanceMeters}m measured. Progress has not been saved.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun MetricBox(label: String, value: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
    }
}

@Composable
private fun BottomActionArea(
    model: SprintScreenState,
    actions: SprintScreenActions,
    snapshotRemainder: Long,
    liveState: SprintLiveState,
    earnedSetsToday: Long,
) {
    val saveState = model.saveState
    val isSaving = saveState?.isSaving == true
    val returningToYoutube = model.returningToYoutube
    val youtubeUnlocked = earnedSetsToday >= YOUTUBE_UNLOCK_SETS
    val fresh = model.fixFresh
    val canStart = model.hasFineLocation && model.gpsProviderEnabled && liveState.isGpsUsable && fresh

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (model.phase) {
            SprintPhase.ACQUIRING_GPS -> {
                Button(
                    colors = pulseButtonColors(),
                    border = PulseActionBorder,
                    onClick = { actions.start(snapshotRemainder) },
                    enabled = canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                    shape = PillShape,
                ) {
                    Text(
                        if (canStart) "Start sprint" else "Waiting for GPS…",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }
            SprintPhase.RECORDING -> {
                Button(
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (liveState.isQualified) PulseGreen else PulseRed,
                        contentColor = Color.White,
                    ),
                    onClick = actions.stop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp),
                    shape = PillShape,
                ) {
                    Text(
                        "STOP",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    )
                }
            }
            SprintPhase.RESULT -> {
                if (saveState?.canRetry == true && !saveState.saveSucceeded) {
                    Button(onClick = { actions.retry() }, enabled = !isSaving,
                        modifier = Modifier.fillMaxWidth()) { Text("Retry saving") }
                }
                Button(
                    colors = pulseButtonColors(),
                    border = PulseActionBorder,
                    onClick = {
                        actions.continueAfterResult(
                            returningToYoutube,
                            youtubeUnlocked,
                            saveState?.newlyEarnedSets ?: 0L,
                        )
                    },
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 60.dp),
                    shape = PillShape,
                ) {
                    if (isSaving) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                strokeWidth = 2.dp,
                            )
                            Text("Saving results…", style = MaterialTheme.typography.labelLarge)
                        }
                    } else {
                        Text("Continue", style = MaterialTheme.typography.labelLarge)
                    }
                }

                OutlinedButton(
                    onClick = {
                        actions.again(snapshotRemainder)
                    },
                    enabled = !isSaving,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = PillShape,
                    border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
                ) {
                    Text("Sprint again")
                }
            }
        }
    }
}

