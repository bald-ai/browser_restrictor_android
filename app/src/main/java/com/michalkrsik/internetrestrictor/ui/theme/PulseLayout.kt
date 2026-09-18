package com.michalkrsik.internetrestrictor.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.ui.components.ExercisePointsProgress
import com.michalkrsik.internetrestrictor.ui.dashboard.carryExpirationRemainingMs
import java.time.ZoneId
import kotlinx.coroutines.delay

/** The approved phone artwork has a 363px content viewport. Scale uniformly by width. */
@Composable
fun PulseScreen(content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onSurface) {
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        val density = LocalDensity.current
        val scale = maxWidth.value / 363f
        val viewportHeight = maxHeight / scale
        CompositionLocalProvider(LocalDensity provides Density(density.density * scale, density.fontScale)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Column(Modifier.fillMaxWidth().heightIn(min = viewportHeight).padding(start = 23.dp, end = 23.dp, top = 17.dp, bottom = 22.dp), content = content)
            }
        }
    }
    }
}

@Composable
fun PulseText(text: String, size: Int = 11, modifier: Modifier = Modifier, muted: Boolean = false, weight: FontWeight = FontWeight.Normal, color: Color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface) {
    Text(text, modifier, color = color, style = MaterialTheme.typography.bodySmall.copy(fontSize = size.sp, lineHeight = (size * 1.43f).sp, fontWeight = weight))
}

@Composable
fun PulseIcon(name: String, size: Int = 18, color: Color = MaterialTheme.colorScheme.onSurface) {
    val data = when(name) {
        "settings" -> "M4 21V14 M4 10V3 M12 21V12 M12 8V3 M20 21V16 M20 12V3 M1 14H7 M9 8H15 M17 16H23"
        "activity" -> "M2 12H6L9 3L15 21L18 12H22"
        "arrow" -> "M7 17L17 7 M7 7H17V17"
        "back" -> "M19 12H5 M12 19L5 12L12 5"
        "plus" -> "M12 5V19 M5 12H19"
        "shield" -> "M12 22S3 18 3 11V5L12 2L21 5V11C21 18 12 22 12 22 M9 12L11 14L15 10"
        "check" -> "M20 6L9 17L4 12"
        "play" -> "M6 3L20 12L6 21Z"
        "help" -> "M9 9C9 5 15 5 15 9C15 12 12 11 12 14 M12 17H12.01 M22 12A10 10 0 1 1 2 12A10 10 0 1 1 22 12"
        "pause" -> "M6 4H9V20H6Z M15 4H18V20H15Z"
        else -> "M12 3V21"
    }
    val path = remember(data) { PathParser().parsePathString(data).toPath() }
    Canvas(Modifier.size(size.dp)) { scale(this.size.width / 24f, this.size.height / 24f, pivot = androidx.compose.ui.geometry.Offset.Zero) { drawPath(path, color, style = Stroke(width = 1.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)) } }
}

@Composable
fun PulseBack(label: String = "Dashboard", onClick: () -> Unit) {
    Row(Modifier.clickable(role = Role.Button, onClick = onClick).padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PulseIcon("back", 16, EditorialTheme.colors.accent)
        PulseText(label, color = EditorialTheme.colors.accent)
    }
}

@Composable
fun PulseNav(settings: Boolean = false, onToday: () -> Unit, onSettings: () -> Unit) {
    HorizontalDivider(color = EditorialTheme.colors.hairline)
    Row(Modifier.fillMaxWidth().padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(65.dp, Alignment.CenterHorizontally)) {
        listOf(false to "Today", true to "Settings").forEach { (isSettings, label) ->
            val color = if (settings == isSettings) EditorialTheme.colors.accent else MaterialTheme.colorScheme.onSurfaceVariant
            Column(Modifier.clickable(role = Role.Button, onClick = if(isSettings) onSettings else onToday).padding(horizontal = 5.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PulseIcon(if(isSettings) "settings" else "activity", 18, color)
                PulseText(label, 9, color = color)
            }
        }
    }
}

@Composable
fun PulseSection(title: String, detail: String = "") {
    Row(Modifier.fillMaxWidth().padding(top = 17.dp, bottom = 12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        PulseText(title, 13, weight = FontWeight.SemiBold)
        PulseText(detail, 10, muted = true)
    }
}

@Composable
fun PulseControlGroup(content: @Composable ColumnScope.() -> Unit) {
    val day = MaterialTheme.colorScheme.background == PaperLight
    if(day) {
        RestrictorTheme(ThemePreference.DARK) {
            Column(Modifier.fillMaxWidth().background(Color(0xFF252C1F), RoundedCornerShape(12.dp)).padding(horizontal = 16.dp).padding(bottom = 16.dp), content = content)
        }
    } else Column(Modifier.fillMaxWidth(), content = content)
}

@Composable
fun PulseCarryOver(snapshot: StateSnapshot) {
    if (com.michalkrsik.internetrestrictor.BuildConfig.IS_V1) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PulseText("PROGRESS TO NEXT CREDIT", 9)
                PulseText("Resets ${snapshot.configuration.settings.dayReset}", 9, muted = true)
            }
            LinearProgressIndicator(progress = { snapshot.taskCreditProgress.toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp), color = EditorialTheme.colors.accent, trackColor = EditorialTheme.colors.accent.copy(alpha = 0.15f), drawStopIndicator = {})
            PulseText("${(snapshot.taskCreditProgress * 100).toInt()}% · any task contributes", 10, muted = true)
        }
        return
    }
    var now by remember { mutableLongStateOf(snapshot.wallNowMs) }
    LaunchedEffect(Unit) { while(true) { now = System.currentTimeMillis(); delay(1000) } }
    val minutes = carryExpirationRemainingMs(now, ZoneId.systemDefault()) / 60_000
    Column(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 3.dp)) {
        HorizontalDivider(color = EditorialTheme.colors.hairlineSoft)
        Row(Modifier.fillMaxWidth().padding(top = 15.dp, bottom = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("EXERCISE CARRY-OVER", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = .8.sp))
            Column(horizontalAlignment = Alignment.End) {
                Text("EXPIRES IN", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, letterSpacing = .7.sp))
                Spacer(Modifier.height(5.dp))
                PulseText("${minutes / 60}h ${minutes % 60}m", 13)
            }
        }
        ExercisePointsProgress(snapshot.exercisePointSixthsToday)
        HorizontalDivider(Modifier.padding(top = 15.dp), color = EditorialTheme.colors.hairlineSoft)
    }
}

@Composable
fun PulsePrimary(label: String, onClick: () -> Unit, exercise: Boolean = false, subtitle: String? = null, enabled: Boolean = true) {
    val day = MaterialTheme.colorScheme.background == PaperLight
    val shape = RoundedCornerShape(7.dp)
    Box(Modifier.fillMaxWidth().padding(bottom = if(day) 3.dp else 0.dp).then(if(day) Modifier.background(PulseCharcoal, shape).padding(bottom = 3.dp) else Modifier)) {
        Row(Modifier.fillMaxWidth().heightIn(min = if(subtitle != null) 64.dp else 47.dp).background(if(enabled) PulseYellow else MaterialTheme.colorScheme.surfaceVariant, shape).border(if(day) 1.5.dp else 0.dp, if(day) PulseCharcoal else Color.Transparent, shape).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = if(subtitle != null) 16.dp else 12.dp, vertical = 10.dp), horizontalArrangement = if(exercise) Arrangement.spacedBy(11.dp) else Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if(exercise) PulseIcon(if(subtitle != null) "activity" else "plus", 20, PulseCharcoal)
            Column(if(exercise) Modifier.weight(1f) else Modifier) {
                Text(label.uppercase(), color = PulseCharcoal, style = MaterialTheme.typography.labelLarge.copy(fontSize = if(subtitle != null) 14.sp else 12.sp, letterSpacing = .5.sp, fontWeight = FontWeight.ExtraBold))
                if(subtitle != null) { Spacer(Modifier.height(4.dp)); PulseText(subtitle, 10, color = PulseCharcoal) }
            }
            if(exercise) PulseIcon("arrow", 19, PulseCharcoal)
        }
    }
}
