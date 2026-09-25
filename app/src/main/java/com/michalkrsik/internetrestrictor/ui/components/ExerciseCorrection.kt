package com.michalkrsik.internetrestrictor.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.appContainer
import com.michalkrsik.internetrestrictor.domain.*
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun exerciseMeasurement(exercise: ExerciseId, value: Long): String =
    "$value ${if (exercise == ExerciseId.PLANK) "seconds" else "repetitions"}"

@Composable
fun LastExerciseCard(snapshot: StateSnapshot) {
    val receipt = snapshot.lastExercise ?: return
    var editing by rememberSaveable(receipt.id) { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("LAST SET", style = MaterialTheme.typography.labelSmall)
            Text(ExercisePolicies.find(receipt.exercise)!!.displayName, style = MaterialTheme.typography.titleMedium)
            Text(exerciseMeasurement(receipt.exercise, receipt.value), style = MaterialTheme.typography.headlineSmall)
            Text("+${receipt.awardedSets * EXERCISE_SET_REWARD_MINUTES} min · " +
                DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())
                    .format(Instant.ofEpochMilli(receipt.recordedAtWallMs)))
            if (receipt.corrected) Text("Corrected from ${ExercisePolicies.find(receipt.originalExercise)!!.displayName}: " +
                exerciseMeasurement(receipt.originalExercise, receipt.originalValue), style = MaterialTheme.typography.bodySmall)
            else if (receipt.flagged) Text("Flagged as incorrect", style = MaterialTheme.typography.bodySmall)
            if (snapshot.canCorrectLastExercise) TextButton(onClick = { editing = true }) { Text("Wrong result?") }
            else Text("Corrections closed at the daily reset.", style = MaterialTheme.typography.bodySmall)
        }
    }
    if (editing) ExerciseCorrectionDialog(receipt, onDismiss = { editing = false }, onSaved = { editing = false })
}

@Composable
fun ExerciseCorrectionDialog(receipt: ExerciseReceipt, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val repository = LocalContext.current.appContainer.repository
    ExerciseCorrectionForm(receipt, onDismiss, onSaved, repository::correctExercise)
}

/** Renders and submits a correction through the supplied persistence operation. */
@Composable
internal fun ExerciseCorrectionForm(
    receipt: ExerciseReceipt,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
    saveCorrection: suspend (String, ExerciseId, Long, Boolean) -> RewardSaveResult,
) {
    val scope = rememberCoroutineScope()
    var exercise by rememberSaveable(receipt.id) { mutableStateOf(receipt.exercise) }
    var valueText by rememberSaveable(receipt.id) { mutableStateOf(receipt.value.toString()) }
    var exact by rememberSaveable(receipt.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val value = valueText.toLongOrNull()?.takeIf { it in 0..9_999 }
    fun save(flagOnly: Boolean) {
        if (busy || (!flagOnly && value == null)) return
        busy = true
        error = null
        scope.launch {
            try {
                when (val result = saveCorrection(receipt.id, exercise, value ?: receipt.value, flagOnly)) {
                    is RewardSaveResult.Saved -> onSaved()
                    is RewardSaveResult.Rejected -> error = result.reason
                    RewardSaveResult.StorageFailure -> error = "Could not save. Your previous result is unchanged. Try again."
                }
            } finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Correct last set") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Reviewer: ${ExercisePolicies.find(receipt.originalExercise)!!.displayName} · " +
                    exerciseMeasurement(receipt.originalExercise, receipt.originalValue))
                ExercisePolicies.credited.forEach { policy ->
                    TextButton(enabled = !busy, onClick = { if (exercise != policy.id) { exercise = policy.id; valueText = "0" } }) {
                        Text("${if (exercise == policy.id) "✓ " else ""}${policy.displayName}")
                    }
                }
                Text(exerciseMeasurement(exercise, value ?: 0), style = MaterialTheme.typography.headlineMedium)
                listOf(listOf(-3, -2, -1), listOf(1, 2, 3)).forEach { steps ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        steps.forEach { step ->
                            OutlinedButton(modifier = Modifier.weight(1f), enabled = !busy,
                                onClick = { valueText = ((value ?: 0) + step).coerceIn(0, 9_999).toString() }) {
                                Text(if (step > 0) "+$step" else "$step")
                            }
                        }
                    }
                }
                if (exact) OutlinedTextField(value = valueText, onValueChange = { valueText = it },
                    enabled = !busy, label = { Text(if (exercise == ExerciseId.PLANK) "Exact seconds" else "Exact count") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true, isError = value == null,
                    supportingText = { if (value == null) Text("Enter a whole number from 0 to 9,999") })
                else TextButton(enabled = !busy, onClick = { exact = true }) { Text("Enter exact count") }
                if (value != null) {
                    val sets = (receipt.pointsBefore + value * exercisePointSixthsPerUnit(exercise)) / EXERCISE_POINT_SIXTHS_PER_BOOST
                    Text("Time earned: ${receipt.awardedSets * 3} → ${sets * 3} min")
                }
                Text("Saving updates your budget, carry-over and unlock progress.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(enabled = !busy, onClick = { save(true) }) { Text("Only flag as incorrect") }
            }
        },
        confirmButton = { TextButton(enabled = !busy && value != null, onClick = { save(false) }) {
            Text(if (busy) "Saving…" else "Save correction")
        } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Cancel") } },
    )
}
