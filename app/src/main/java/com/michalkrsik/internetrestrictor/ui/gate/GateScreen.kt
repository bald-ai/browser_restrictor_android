package com.michalkrsik.internetrestrictor.ui.gate

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michalkrsik.internetrestrictor.domain.GateMode
import com.michalkrsik.internetrestrictor.domain.MINUTE_MS
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.WaitCalculator
import com.michalkrsik.internetrestrictor.ui.components.BudgetBar
import com.michalkrsik.internetrestrictor.ui.components.PauseEmblem
import com.michalkrsik.internetrestrictor.ui.components.friendlyLabel

data class GateActions(
    val onStartSession: (Long) -> Unit,
    val onEmergencySkip: () -> Unit,
    val onPushupChallenge: () -> Unit,
    val onTryAgain: () -> Unit,
    val onLeaveChrome: () -> Unit,
)

@Composable
fun GateScreen(snapshot: StateSnapshot, actions: GateActions) {
    var selectedMinutes by rememberSaveable(snapshot.gateSessionId) { mutableLongStateOf(10) }
    var actionInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.stateRevision, snapshot.canonicalMode) { actionInFlight = false }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = actions.onLeaveChrome) {
                    Text(
                        "←  Leave Chrome",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(26.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PauseEmblem()
                Spacer(Modifier.height(4.dp))
                Text(
                    text = eyebrow(snapshot.canonicalMode),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.5.sp,
                )
                Text(
                    text = headline(snapshot.canonicalMode),
                    style = MaterialTheme.typography.headlineLarge,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = supportingCopy(snapshot),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (snapshot.canonicalMode) {
                    GateMode.PAUSED -> PausedControls(
                        snapshot = snapshot,
                        selectedMinutes = selectedMinutes,
                        onSelect = { selectedMinutes = it },
                        actionInFlight = actionInFlight,
                        onStart = {
                            actionInFlight = true
                            actions.onStartSession(selectedMinutes)
                        },
                        onSkip = {
                            actionInFlight = true
                            actions.onEmergencySkip()
                        },
                        onPushupChallenge = {
                            actionInFlight = true
                            actions.onPushupChallenge()
                        },
                    )
                    GateMode.WAITING -> WaitingControls(
                        snapshot = snapshot,
                        actionInFlight = actionInFlight,
                        onSkip = {
                            actionInFlight = true
                            actions.onEmergencySkip()
                        },
                        onPushupChallenge = {
                            actionInFlight = true
                            actions.onPushupChallenge()
                        },
                    )
                    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> OutOfTimeControls(
                        snapshot = snapshot,
                        actionInFlight = actionInFlight,
                        onPushupChallenge = {
                            actionInFlight = true
                            actions.onPushupChallenge()
                        },
                        onSkip = {
                            actionInFlight = true
                            actions.onEmergencySkip()
                        },
                        onTryAgain = {
                            actionInFlight = true
                            actions.onTryAgain()
                        },
                    )
                    else -> Unit
                }
                snapshot.lastErrorMessage?.let {
                    Text(
                        text = it,
                        modifier = Modifier.padding(vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun PausedControls(
    snapshot: StateSnapshot,
    selectedMinutes: Long,
    onSelect: (Long) -> Unit,
    actionInFlight: Boolean,
    onStart: () -> Unit,
    onSkip: () -> Unit,
    onPushupChallenge: () -> Unit,
) {
    val waitMs = WaitCalculator.requiredFocusMs(selectedMinutes, snapshot.completedNormalUnlocksToday)
    val cost = WaitCalculator.costPerTenSeconds(snapshot.completedNormalUnlocksToday)
    Column(
        Modifier.padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WaitCalculator.sessionMinutes.forEach { minutes ->
                DurationCard(
                    minutes = minutes,
                    selected = minutes == selectedMinutes,
                    onClick = { onSelect(minutes) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            "Today's cost: $cost sec per 10 min unlock. This includes a ${WaitCalculator.formatWait(waitMs)} wait.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onStart,
            enabled = !actionInFlight,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Begin $selectedMinutes minute session", style = MaterialTheme.typography.labelLarge)
        }
        PushupButton(actionInFlight, onPushupChallenge)
        SkipButton(snapshot, actionInFlight, onSkip)
        Budgets(snapshot)
    }
}

@Composable
private fun WaitingControls(
    snapshot: StateSnapshot,
    actionInFlight: Boolean,
    onSkip: () -> Unit,
    onPushupChallenge: () -> Unit,
) {
    val wait = snapshot.waitSnapshot ?: return
    val seconds = WaitCalculator.displaySeconds(wait.remainingFocusMs)
    val animatedProgress by animateFloatAsState(
        targetValue = wait.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 240, easing = LinearEasing),
        label = "focused wait progress",
    )
    Column(
        Modifier.padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        SectionLabel("Wait remaining")
        Column(
            Modifier.fillMaxWidth().semantics {
                contentDescription = "$seconds seconds remaining"
                liveRegion = LiveRegionMode.Polite
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(seconds.toString(), style = MaterialTheme.typography.displayLarge)
            Text("SECONDS", style = MaterialTheme.typography.labelSmall, letterSpacing = 2.sp)
        }
        Box(
            Modifier.fillMaxWidth().height(7.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(100)),
        ) {
            Box(
                Modifier.fillMaxWidth(animatedProgress).height(7.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(100)),
            )
        }
        Text(
            "Keep this pause screen focused to continue.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        PushupButton(actionInFlight, onPushupChallenge)
        SkipButton(snapshot, actionInFlight, onSkip)
        Budgets(snapshot)
    }
}

@Composable
private fun OutOfTimeControls(
    snapshot: StateSnapshot,
    actionInFlight: Boolean,
    onPushupChallenge: () -> Unit,
    onSkip: () -> Unit,
    onTryAgain: () -> Unit,
) {
    Column(
        Modifier.padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Budgets(snapshot)
        PushupButton(actionInFlight, onPushupChallenge)
        SkipButton(snapshot, actionInFlight, onSkip)
        Button(
            onClick = onTryAgain,
            enabled = !actionInFlight,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Try again", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun PushupButton(actionInFlight: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !actionInFlight,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Earn 30 minutes", fontWeight = FontWeight.Bold)
            Text("Complete 5 push-ups · no budget used", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SkipButton(snapshot: StateSnapshot, actionInFlight: Boolean, onClick: () -> Unit) {
    if (!snapshot.skipSnapshot.eligible) return
    val actual = snapshot.skipSnapshot.grantDurationMs
    val label = if (actual < MINUTE_MS) "<1 min" else "${actual / MINUTE_MS} min"
    OutlinedButton(
        onClick = onClick,
        enabled = !actionInFlight,
        modifier = Modifier.fillMaxWidth().height(58.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Use emergency skip · ${snapshot.skipSnapshot.remaining} left today", fontWeight = FontWeight.Bold)
            Text("Opens Chrome for $label", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Budgets(snapshot: StateSnapshot) {
    val enabled = snapshot.budgetSnapshots.filter { it.enabled }
    if (enabled.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        enabled.forEach {
            BudgetBar(
                budget = it,
                label = it.period.friendlyLabel(),
                forceExhausted = snapshot.canonicalMode == GateMode.OUT_OF_TIME_LATCHED &&
                    it.period in snapshot.latchedBlockers,
            )
        }
    }
}

@Composable
private fun DurationCard(
    minutes: Long,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(13.dp)
    Column(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .border(
                width = 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outline.copy(alpha = 0.45f),
                shape = shape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                role = Role.RadioButton
                this.selected = selected
                contentDescription = "$minutes minute session"
            }
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "$minutes min",
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        letterSpacing = 1.7.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun eyebrow(mode: GateMode): String = when (mode) {
    GateMode.PAUSED -> "TAKE A MOMENT"
    GateMode.WAITING -> "ALMOST THERE"
    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> "BUDGET USED"
    else -> "CHROME"
}

private fun headline(mode: GateMode): String = when (mode) {
    GateMode.PAUSED -> "Pause before Chrome."
    GateMode.WAITING -> "Hang on a moment."
    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> "Chrome is out of time."
    else -> "Chrome"
}

private fun supportingCopy(snapshot: StateSnapshot): String = when (snapshot.canonicalMode) {
    GateMode.PAUSED -> "Choose how long you need."
    GateMode.WAITING -> {
        val minutes = (snapshot.waitSnapshot?.selectedDurationMs ?: 0) / MINUTE_MS
        "Your $minutes min session is opening soon."
    }
    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> "Budget used. Try again after reset."
    else -> ""
}
