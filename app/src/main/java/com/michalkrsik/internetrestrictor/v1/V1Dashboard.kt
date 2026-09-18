package com.michalkrsik.internetrestrictor.v1

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michalkrsik.internetrestrictor.domain.SavedTaskReceipt
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.TaskDefinition
import com.michalkrsik.internetrestrictor.ui.components.HealthCard
import com.michalkrsik.internetrestrictor.ui.theme.PulseYellow
import com.michalkrsik.internetrestrictor.ui.theme.PulseCharcoal
import kotlinx.coroutines.launch

@Composable
fun V1Dashboard(snapshot: StateSnapshot, connection: String, onConversation: () -> Unit,
    onSettings: () -> Unit, onTask: (TaskDefinition) -> Unit, onHealth: () -> Unit,
    onRetry: () -> Unit, onCorrect: suspend (SavedTaskReceipt, Double) -> Unit,
    onFallback: suspend (SavedTaskReceipt) -> Unit) {
    var correction by remember { mutableStateOf<SavedTaskReceipt?>(null) }
    var showHistory by remember { mutableStateOf(false) }
    val rules = snapshot.configuration.settings
    val budget = snapshot.budgetSnapshots.firstOrNull()
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("FRICTION", style = MaterialTheme.typography.labelLarge, letterSpacing = 3.sp); Text("Your day, on purpose.", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = PulseYellow, contentColor = PulseCharcoal) {
            Column(Modifier.padding(24.dp)) {
                Row { Text("${((budget?.remainingMs ?: 0L).coerceAtLeast(0) + 59_999) / 60_000}", fontSize = 72.sp, fontWeight = FontWeight.SemiBold); Text(" min", Modifier.padding(top = 40.dp), fontSize = 24.sp) }
                Text("${rules.minutesPerCredit} minutes per ${rules.creditName.removeSuffix("s")} · resets ${nextDailyResetLabel(snapshot.wallNowMs, rules)}", style = MaterialTheme.typography.bodySmall)
                Text("${rules.dailyBudgetMinutes} min daily allowance · extra time from tasks", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(18.dp))
                LinearProgressIndicator(progress = { snapshot.taskCreditProgress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), color = PulseCharcoal, trackColor = PulseCharcoal.copy(alpha = 0.15f), drawStopIndicator = {})
                Text("${(snapshot.taskCreditProgress * 100).toInt()}% toward your next credit · any task contributes", style = MaterialTheme.typography.bodySmall)
            }
        }
        Button(
            onClick = onConversation,
            modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = PulseCharcoal, contentColor = PulseYellow),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 1.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Change your app by voice", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Tell the AI what you want. Review and approve.", style = MaterialTheme.typography.bodySmall)
                }
                Text("›", fontSize = 30.sp)
            }
        }
        Text(connection, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (connection.startsWith("Offline") || connection.contains("waiting")) TextButton(onClick = onRetry) { Text("Retry connection & saved recordings") }
        val unlocked = snapshot.earnedBudgetRoundsToday >= rules.youtubeCredits
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), tonalElevation = 2.dp) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (unlocked) "YouTube unlocked" else "YouTube locked", fontWeight = FontWeight.SemiBold)
                Text("${snapshot.earnedBudgetRoundsToday} of ${rules.youtubeCredits} ${rules.creditName} · resets ${rules.dayReset}", style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Make a little progress", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onConversation) { Text("Edit") }
        }
        snapshot.configuration.tasks.filterNot { it.paused }.forEach { task ->
            OutlinedCard(onClick = { onTask(task) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(18.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(task.name, style = MaterialTheme.typography.titleMedium)
                        Text("${task.unitsPerCredit} ${task.metric} · +${rules.minutesPerCredit} min", style = MaterialTheme.typography.bodyMedium)
                        Text(if (task.verifier == "video") "Video verified · 30 seconds max" else "GPS verified · ${task.gpsMode}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Start ›", Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (snapshot.configuration.tasks.none { !it.paused }) Text("No active tasks. Add one or unpause a task in Customize.")
        HorizontalDivider(Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Recent activity", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { showHistory = true }) { Text("View history") }
        }
        val latest = snapshot.taskReceipts.maxByOrNull { it.measurement.finishedAtWallMs }
        if (latest != null) {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Text("LAST RECORDED RESULT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${latest.measurement.task.name} · ${latest.valueText} ${latest.measurement.task.metric}", style = MaterialTheme.typography.titleMedium)
                    Text(if (latest.rewardEligible) "+${latest.awardedMinutes} min · ${latest.awardedCredits} ${rules.creditName}" else "Saved to history · reward window expired", style = MaterialTheme.typography.bodySmall)
                    if (latest.rewardEligible && !latest.minutesRewardEligible) Text("Browsing-time window expired; daily progress kept.", style = MaterialTheme.typography.bodySmall)
                    if (latest.measurement.correctionEligible && !latest.measurement.manualCompletion && latest.measurement.value >= latest.measurement.task.correctionMinimum && latest.measurement.id in snapshot.modifiableTaskReceiptIds) TextButton(onClick = { correction = latest }) { Text("Correct measurement") }
                    if (latest.measurement.fallbackEligible && !latest.measurement.manualCompletion && latest.measurement.id in snapshot.modifiableTaskReceiptIds) {
                        var saving by remember(latest) { mutableStateOf(false) }
                        var error by remember(latest) { mutableStateOf<String?>(null) }
                        val scope = rememberCoroutineScope()
                        Text("GPS coverage was insufficient. Confirm only if you completed the task.", style = MaterialTheme.typography.bodySmall)
                        TextButton(enabled = !saving, onClick = {
                            saving = true
                            scope.launch { try { onFallback(latest) } catch (e: Exception) { error = e.message } finally { saving = false } }
                        }) { Text(if (saving) "Saving…" else "I completed this task · 1 credit") }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
        if (latest == null) Text("Completed recordings will appear here.", style = MaterialTheme.typography.bodySmall)
        HealthCard(snapshot.enforcementHealth, snapshot.usageAccessGranted, onHealth, compact = true)
        Text("A pause, a choice, a little momentum.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showHistory) AlertDialog(onDismissRequest = { showHistory = false }, title = { Text("Your activity") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (snapshot.taskReceipts.isEmpty()) Text("Your verified tasks will appear here.")
            snapshot.taskReceipts.sortedByDescending { it.measurement.finishedAtWallMs }.take(50).forEach { receipt ->
                Column { Text("${receipt.measurement.task.name} · ${receipt.valueText} ${receipt.measurement.task.metric}"); Text("+${receipt.awardedMinutes} min${if (receipt.corrected) " · corrected" else ""}", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }, confirmButton = { TextButton(onClick = { showHistory = false }) { Text("Done") } })
    correction?.takeIf { it.measurement.id in snapshot.modifiableTaskReceiptIds }?.let { receipt ->
        var value by remember(receipt) { mutableStateOf(receipt.valueText) }
        var error by remember { mutableStateOf<String?>(null) }
        var saving by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        AlertDialog(onDismissRequest = { if (!saving) correction = null }, title = { Text("Correct ${receipt.measurement.task.name}") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("The verifier measured ${receipt.measurement.measuredUnitsText} ${receipt.measurement.task.metric}. Rewards use this recording’s original rules.")
                OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(receipt.measurement.task.metric) }, keyboardOptions = KeyboardOptions(keyboardType = if (receipt.measurement.task.metric == "repetitions") KeyboardType.Number else KeyboardType.Decimal))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(enabled = !saving && parseCorrectionValue(value, receipt.measurement.task.metric) != null, onClick = {
            saving = true
            scope.launch { try { onCorrect(receipt, parseCorrectionValue(value, receipt.measurement.task.metric)!!); correction = null } catch (e: Exception) { error = e.message } finally { saving = false } }
        }) { Text(if (saving) "Saving…" else "Save correction") } }, dismissButton = { TextButton(onClick = { correction = null }, enabled = !saving) { Text("Cancel") } })
    }
}
