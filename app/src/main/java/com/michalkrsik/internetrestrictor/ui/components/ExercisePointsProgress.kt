package com.michalkrsik.internetrestrictor.ui.components

import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun ExercisePointsProgress(sixths: Long, modifier: Modifier = Modifier) {
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val progress = (sixths / 30f).coerceIn(0f, 1f)
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val ticks = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val fill = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("POINTS", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = muted)
                IconButton(onClick = { showInfo = !showInfo }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Info, contentDescription = "How exercise points work", modifier = Modifier.size(16.dp), tint = muted)
                }
            }
            Text("+3 min", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = muted)
        }
        if (showInfo) {
            // This component also runs in an accessibility overlay. A platform dialog
            // needs an Activity window token and would crash the blocking service.
            Surface(
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Exercise points", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Each push-up earns 1 point. Earn 5 points for +3 minutes. Partial points carry over until 04:00. Previously earned exercise points still count.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { showInfo = false }, modifier = Modifier.align(Alignment.End)) {
                        Text("Got it")
                    }
                }
            }
        }
        Canvas(
            Modifier.fillMaxWidth().height(26.dp).padding(horizontal = 6.dp)
                .semantics {
                    contentDescription = "Exercise points toward a 3-minute boost"
                    progressBarRangeInfo = ProgressBarRangeInfo(progress * 5, 0f..5f)
                },
        ) {
            drawExercisePointTrack(progress, track, ticks, fill)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (point in 0..5) {
                Text(
                    "$point", modifier = Modifier.width(12.dp), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), color = muted,
                )
            }
        }
    }
}

/** Geometry is independent of composition and uses the caller's size and density. */
internal fun androidx.compose.ui.graphics.drawscope.DrawScope.drawExercisePointTrack(
    progress: Float,
    track: androidx.compose.ui.graphics.Color,
    ticks: androidx.compose.ui.graphics.Color,
    fill: androidx.compose.ui.graphics.Color,
) {
    val y = size.height / 2
    drawLine(track, Offset(0f, y), Offset(size.width, y), 6.dp.toPx(), StrokeCap.Round)
    if (progress > 0f) {
        drawLine(fill, Offset(0f, y), Offset(size.width * progress, y), 6.dp.toPx(), StrokeCap.Round)
    }
    for (point in 0..5) {
        val x = size.width * point / 5
        drawLine(ticks, Offset(x, y - 7.dp.toPx()), Offset(x, y + 7.dp.toPx()), 1.dp.toPx())
    }
}
