package com.michalkrsik.internetrestrictor.ui.gate

import com.michalkrsik.internetrestrictor.ui.theme.*
import androidx.compose.foundation.layout.width
import com.michalkrsik.internetrestrictor.ui.components.ExercisePointsProgress
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michalkrsik.internetrestrictor.domain.GateMode
import com.michalkrsik.internetrestrictor.domain.MINUTE_MS
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.WaitCalculator
import com.michalkrsik.internetrestrictor.domain.YOUTUBE_UNLOCK_SETS
import com.michalkrsik.internetrestrictor.ui.components.BudgetBar
import com.michalkrsik.internetrestrictor.ui.components.PauseEmblem
import com.michalkrsik.internetrestrictor.ui.components.formatCompactTime
import com.michalkrsik.internetrestrictor.ui.components.friendlyLabel
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.PulsePanel
import com.michalkrsik.internetrestrictor.ui.theme.pulseButtonColors
import com.michalkrsik.internetrestrictor.ui.theme.PulseActionBorder
import com.michalkrsik.internetrestrictor.ui.theme.PulseCharcoal
import com.michalkrsik.internetrestrictor.ui.theme.SerifNote
import com.michalkrsik.internetrestrictor.ui.theme.editorialTitle

data class GateActions(
    val onStartSession: (Long) -> Unit,
    val onEmergencySkip: () -> Unit,
    val onExerciseChallenge: () -> Unit,
    val onTryAgain: () -> Unit,
    val onLeaveBrowser: () -> Unit,
)

@Composable
fun GateScreen(snapshot: StateSnapshot, actions: GateActions) {
    if (snapshot.canonicalMode == GateMode.WAITING) {
        WaitingGate(snapshot, actions.onLeaveBrowser)
        return
    }
    var selectedMinutes by rememberSaveable(snapshot.gateSessionId, snapshot.settings.sessionMinutes) { mutableLongStateOf(snapshot.settings.sessionMinutes[1]) }
    var actionInFlight by remember { mutableStateOf(false) }
    LaunchedEffect(snapshot.stateRevision, snapshot.canonicalMode) { actionInFlight = false }
    PulseScreen {
        PulseBack(if(snapshot.canonicalMode == GateMode.YOUTUBE_LOCKED) "Leave YouTube" else "Leave browser", actions.onLeaveBrowser)
        Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PauseEmblem()
            Spacer(Modifier.height(18.dp))
            Text(eyebrow(snapshot.canonicalMode), style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.2.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(13.dp))
            Text(headline(snapshot.canonicalMode).uppercase(), textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineLarge.copy(fontSize = 41.sp, lineHeight = 42.sp, letterSpacing = (-1).sp))
            Spacer(Modifier.height(10.dp))
            Text(supportingCopy(snapshot), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 21.sp))
        }
        when(snapshot.canonicalMode) {
            GateMode.PAUSED -> PausedControls(snapshot, selectedMinutes, { selectedMinutes = it }, actionInFlight,
                { actionInFlight = true; actions.onStartSession(selectedMinutes) },
                { actionInFlight = true; actions.onEmergencySkip() },
                { actionInFlight = true; actions.onExerciseChallenge() })
            GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> OutOfTimeControls(snapshot, actionInFlight,
                { actionInFlight = true; actions.onExerciseChallenge() },
                { actionInFlight = true; actions.onEmergencySkip() },
                { actionInFlight = true; actions.onTryAgain() })
            GateMode.YOUTUBE_LOCKED -> YouTubeLockedControls(snapshot, actionInFlight, { actionInFlight = true; actions.onExerciseChallenge() })
            else -> Unit
        }
        snapshot.lastErrorMessage?.let { PulseText(it, 12, Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.weight(1f))
        PulseText("Your attention belongs to you.", 9, Modifier.align(Alignment.CenterHorizontally).padding(top = 22.dp), muted = true)
    }
}

@Composable
private fun YouTubeLockedControls(
    snapshot: StateSnapshot,
    actionInFlight: Boolean,
    onExerciseChallenge: () -> Unit,
) {
    Column(
        Modifier.padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            colors = pulseButtonColors(),
            border = PulseActionBorder,
            onClick = onExerciseChallenge,
            enabled = !actionInFlight,
            modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
            shape = PillShape,
        ) {
            Text("Start exercise", style = MaterialTheme.typography.labelLarge)
        }
        Text(
            "${snapshot.earnedBudgetRoundsToday}/$YOUTUBE_UNLOCK_SETS sets today · unlocks until 04:00",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Every 5 points earns +3 minutes",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        CarryOverRows(snapshot)
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
    onExerciseChallenge: () -> Unit,
) {
    val remaining = ((snapshot.budgetSnapshots.firstOrNull { it.enabled }?.remainingMs ?: 0L) + 59_999) / 60_000
    Column {
        PulseControlGroup {
            PulseSection("Session length", "$remaining min available")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                snapshot.settings.sessionMinutes.forEach { minutes -> DurationCard(minutes, minutes == selectedMinutes, { onSelect(minutes) }, Modifier.weight(1f)) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(4.dp).background(PulseYellow, RoundedCornerShape(50)))
                Spacer(Modifier.width(5.dp))
                PulseText("A 12-second pause before each session", 9, muted = true)
            }
        }
        Spacer(Modifier.height(5.dp))
        PulseCarryOver(snapshot)
        Spacer(Modifier.height(18.dp))
        PulsePrimary("Start exercise", onExerciseChallenge, exercise = true, subtitle = "Earn 3 minutes with your next set", enabled = !actionInFlight)
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = onStart, enabled = !actionInFlight, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(7.dp), border = BorderStroke(1.dp, EditorialTheme.colors.hairline), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) {
            Text("CONTINUE FOR $selectedMinutes MINUTES", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, letterSpacing = .5.sp))
            Spacer(Modifier.width(8.dp))
            PulseIcon("arrow", 17)
        }
        Spacer(Modifier.height(5.dp))
        SkipButton(snapshot, actionInFlight, onSkip)
    }
}

@Composable
private fun WaitingGate(snapshot: StateSnapshot, onCancel: () -> Unit) {
    val wait = snapshot.waitSnapshot ?: return
    val seconds = WaitCalculator.displaySeconds(wait.remainingFocusMs)
    val elapsedProgress by animateFloatAsState(
        targetValue = wait.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 240, easing = LinearEasing),
        label = "focused wait progress",
    )
    val selectedMinutes = wait.selectedDurationMs / MINUTE_MS
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            TextButton(onClick = onCancel) {
                Text(
                    "←  Cancel",
                    style = MaterialTheme.typography.labelLarge,
                    color = EditorialTheme.colors.accent,
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(bottom = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SectionLabel("Unlocking browser")
                Spacer(Modifier.height(10.dp))
                Text(
                    text = buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                color = EditorialTheme.colors.accent,
                                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            ),
                        ) { append("$selectedMinutes min") }
                        append(" session")
                    },
                    style = MaterialTheme.typography.headlineLarge,
                )
                Spacer(Modifier.height(42.dp))
                Box(
                    modifier = Modifier
                        .size(214.dp)
                        .semantics {
                            contentDescription = "$seconds seconds remaining"
                            liveRegion = LiveRegionMode.Polite
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        progress = { elapsedProgress },
                        modifier = Modifier.fillMaxSize(),
                        color = EditorialTheme.colors.accent,
                        trackColor = EditorialTheme.colors.hairlineSoft,
                        strokeWidth = 5.dp,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            seconds.toString(),
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontSize = 92.sp,
                                letterSpacing = (-3).sp,
                            ),
                        )
                        Text(
                            "SECONDS",
                            style = MaterialTheme.typography.labelSmall,
                            color = EditorialTheme.colors.ink3,
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
                CountdownBudget(snapshot)
            }
        }
    }
}

@Composable
private fun CountdownBudget(snapshot: StateSnapshot) {
    val budget = snapshot.budgetSnapshots.firstOrNull { it.enabled } ?: return
    val remaining = budget.remainingMs ?: 0
    val remainingFraction = (1f - budget.percentage / 100f).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth(.64f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Daily budget", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${formatCompactTime(remaining)} left", style = MaterialTheme.typography.labelMedium)
        }
        Box(
            Modifier.fillMaxWidth().height(6.dp)
                .background(EditorialTheme.colors.hairlineSoft, RoundedCornerShape(100)),
        ) {
            Box(
                Modifier.fillMaxWidth(remainingFraction).height(6.dp)
                    .background(EditorialTheme.colors.accent, RoundedCornerShape(100)),
            )
        }
    }
}

@Composable
private fun OutOfTimeControls(
    snapshot: StateSnapshot,
    actionInFlight: Boolean,
    onExerciseChallenge: () -> Unit,
    onSkip: () -> Unit,
    onTryAgain: () -> Unit,
) {
    Column(
        Modifier.padding(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Budgets(snapshot)
        ExerciseButton(snapshot, actionInFlight, onExerciseChallenge)
        SkipButton(snapshot, actionInFlight, onSkip)
        Button(
            onClick = onTryAgain,
            enabled = !actionInFlight,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
        ) {
            Text("Try again", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ExerciseButton(snapshot: StateSnapshot, actionInFlight: Boolean, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PulseCarryOver(snapshot)
        PulsePrimary("Start exercise", onClick, exercise = true, subtitle = "Earn 3 minutes with your next set", enabled = !actionInFlight)
    }
}

@Composable
internal fun CarryOverRows(snapshot: StateSnapshot) { PulseCarryOver(snapshot) }

@Composable
private fun SkipButton(snapshot: StateSnapshot, actionInFlight: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = snapshot.skipSnapshot.eligible && !actionInFlight, modifier = Modifier.fillMaxWidth()) {
        PulseText("Emergency unlock", 11, muted = true)
        Spacer(Modifier.width(7.dp))
        PulseText("${snapshot.skipSnapshot.remaining} available · 15 min", 9, muted = true)
    }
    HorizontalDivider(color = EditorialTheme.colors.hairline)
}

@Composable
private fun Budgets(snapshot: StateSnapshot) {
    val enabled = snapshot.budgetSnapshots.filter { it.enabled }
    if (enabled.isEmpty()) return
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(15.dp)) {
        HorizontalDivider(color = EditorialTheme.colors.hairline)
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
    val color = if(selected) PulseCharcoal else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.height(75.dp).clip(shape).background(if(selected) PulseYellow else CardDark).border(1.dp, if(selected) PulseYellow else EditorialTheme.colors.hairline, shape).clickable(role = Role.RadioButton, onClick = onClick).semantics { this.selected = selected; contentDescription = "$minutes minute session" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        PulseText("$minutes", 24, weight = FontWeight.Medium, color = color)
        PulseText("minutes", 10, color = color)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = EditorialTheme.colors.ink3,
    )
}

private fun eyebrow(mode: GateMode): String = when (mode) {
    GateMode.PAUSED -> "A MOMENT BEFORE YOU BROWSE"
    GateMode.WAITING -> "ALMOST THERE"
    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> "BUDGET USED"
    GateMode.YOUTUBE_LOCKED -> "MORNING GATE"
    else -> "BROWSER"
}

private fun headline(mode: GateMode): String = when (mode) {
    GateMode.PAUSED -> "MAKE IT\nINTENTIONAL."
    GateMode.WAITING -> "Hang on a moment."
    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> "Browser time is used up."
    GateMode.YOUTUBE_LOCKED -> "Exercise before YouTube."
    else -> "Browser"
}

private fun supportingCopy(snapshot: StateSnapshot): String = when (snapshot.canonicalMode) {
    GateMode.PAUSED -> "Choose how long you need.\nThe rest of your day is yours."
    GateMode.WAITING -> {
        val minutes = (snapshot.waitSnapshot?.selectedDurationMs ?: 0) / MINUTE_MS
        "Your $minutes min session is opening soon."
    }
    GateMode.OUT_OF_TIME, GateMode.OUT_OF_TIME_LATCHED -> "Budget used. Try again after reset."
    GateMode.YOUTUBE_LOCKED -> "Any combination of 4 sets unlocks it until 04:00."
    else -> ""
}
