package com.michalkrsik.internetrestrictor.ui.dashboard

import com.michalkrsik.internetrestrictor.ui.components.ExercisePointsProgress
import com.michalkrsik.internetrestrictor.ui.theme.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michalkrsik.internetrestrictor.domain.DailyUsageReport
import com.michalkrsik.internetrestrictor.domain.ActivityEventType
import com.michalkrsik.internetrestrictor.domain.MonthlyPushupCalculator
import com.michalkrsik.internetrestrictor.domain.MonthlyPushupCycle
import com.michalkrsik.internetrestrictor.domain.ProtectionHealth
import com.michalkrsik.internetrestrictor.domain.PeriodCalculator
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.ui.components.HealthCard
import com.michalkrsik.internetrestrictor.ui.components.formatReportTime
import com.michalkrsik.internetrestrictor.ui.components.healthCopy
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PulseDisplay
import com.michalkrsik.internetrestrictor.ui.theme.PulseYellow
import com.michalkrsik.internetrestrictor.ui.theme.PulseCharcoal
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.pulseButtonColors
import com.michalkrsik.internetrestrictor.ui.theme.PulseActionBorder
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    snapshot: StateSnapshot,
    onOpenSettings: () -> Unit,
    onTestBrowser: () -> Unit,
    onStartExercise: () -> Unit,
    onHealthAction: () -> Unit,
    onStartSprint: () -> Unit = {},
) {
    var showReports by rememberSaveable { mutableStateOf(false) }
    var showMonthly by rememberSaveable { mutableStateOf(false) }
    PulseScreen {
        DashboardHeader(onOpenSettings)
        Spacer(Modifier.height(15.dp))
        ProtectionLine(snapshot)
        Spacer(Modifier.height(18.dp))
        AvailableTimeCard(snapshot)
        PulseCarryOver(snapshot)
        com.michalkrsik.internetrestrictor.ui.components.LastExerciseCard(snapshot)
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(15.dp)) {
            Column(Modifier.weight(1f).clickable { showReports = true }.padding(start = 2.dp)) {
                Text("EXERCISE SETS", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${snapshot.todayUsageReport.exerciseSets}", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Manrope, fontSize = 27.sp, fontWeight = FontWeight.Medium))
                    PulseText("today", 10, Modifier.padding(start = 7.dp), muted = true)
                }
            }
            Column(Modifier.weight(1f).clickable { showMonthly = true }.drawBehind { drawLine(androidx.compose.ui.graphics.Color(0xFFADB979), Offset.Zero, Offset(0f, size.height), 1.dp.toPx()) }.padding(start = 18.dp)) {
                Text("PUSH-UPS", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${snapshot.currentMonthlyPushupCycle.totalPushups}", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = Manrope, fontSize = 27.sp, fontWeight = FontWeight.Medium))
                    PulseText("this cycle", 10, Modifier.padding(start = 7.dp), muted = true)
                }
            }
        }
        val unlocked = snapshot.earnedBudgetRoundsToday >= 4
        val day = MaterialTheme.colorScheme.background == PaperLight
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = if(day) androidx.compose.ui.graphics.Color(0xFF252C1F) else androidx.compose.ui.graphics.Color.Transparent, border = BorderStroke(1.dp, if(day) PulseCharcoal else EditorialTheme.colors.hairline)) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PulseIcon("play", 18, PulseYellow)
                Column(Modifier.weight(1f)) {
                    PulseText(if(unlocked) "YouTube unlocked" else "YouTube locked", 12, weight = FontWeight.Medium, color = InkDark)
                    Spacer(Modifier.height(4.dp))
                    PulseText("${snapshot.earnedBudgetRoundsToday.coerceAtMost(4)} of 4 sets · until 04:00", 10, color = Ink2Dark)
                }
                if(unlocked) PulseIcon("check", 16, PulseYellow)
            }
        }
        Column(Modifier.clickable { showReports = true }) {
            PulseSection("This week", "Exercise sets")
            val reports = snapshot.currentWeekUsageReports
            val maximum = (reports.maxOfOrNull { it.exerciseSets } ?: 1).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(71.dp).padding(bottom = 18.dp), horizontalArrangement = Arrangement.SpaceAround, verticalAlignment = Alignment.Bottom) {
                reports.forEach { report ->
                    val current = report.localDayKey == snapshot.todayUsageReport.localDayKey
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Box(Modifier.width(14.dp).height((report.exerciseSets.toFloat() / maximum * 45).coerceAtLeast(2f).dp).background(if(current) EditorialTheme.colors.accent else EditorialTheme.colors.hairline, RoundedCornerShape(4.dp,4.dp,2.dp,2.dp)))
                        PulseText(dayName(report.localDayKey).take(1), 9, muted = true)
                    }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        PulsePrimary("Start push-ups", onStartExercise, exercise = true)
        Spacer(Modifier.height(8.dp))
        PulsePrimary("Start sprint", onStartSprint, exercise = true)
        PulseText("Available time resets at ${snapshot.configuration.settings.dayReset}", 9, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 11.dp), muted = true)
        val health = healthCopy(snapshot.enforcementHealth, snapshot.usageAccessGranted)
        if(!health.positive || health.action != null) HealthCard(snapshot.enforcementHealth, snapshot.usageAccessGranted, onHealthAction, compact = true)
        Spacer(Modifier.weight(1f))
        PulseNav(onToday = {}, onSettings = onOpenSettings)
    }
    if(showReports || showMonthly) {
        AlertDialog(onDismissRequest = { showReports = false; showMonthly = false }, title = { Text(if(showMonthly) "Monthly push-ups" else "Activity") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState())) { if(showMonthly) MonthlyPushupHistory(snapshot) else { WeekContent(snapshot); Spacer(Modifier.height(24.dp)); DayContent(snapshot) } }
        }, confirmButton = { TextButton(onClick = { showReports = false; showMonthly = false }) { Text("Done") } })
    }
}

@Composable
private fun AvailableTimeCard(snapshot: StateSnapshot) {
    val budget = snapshot.budgetSnapshots.firstOrNull { it.enabled }
    val remainingMs = (budget?.remainingMs ?: 0L).coerceAtLeast(0L)
    val remainingMinutes = (remainingMs + 59_999L) / 60_000L
    val remainingLabel = if (remainingMs in 1..59_999L) "<1" else remainingMinutes.toString()
    val remainingFraction = budget?.let { (1f - it.percentage / 100f).coerceIn(0f, 1f) } ?: 0f
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).rotate(-1.5f).then(if(MaterialTheme.colorScheme.background == PaperLight) Modifier.drawBehind { drawRoundRect(PulseCharcoal, topLeft = Offset(3.dp.toPx(), 4.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(16.dp.toPx())) } else Modifier),
        color = PulseYellow,
        contentColor = PulseCharcoal,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, PulseCharcoal),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text("AVAILABLE TO BROWSE", color = androidx.compose.ui.graphics.Color(0xFF455922), style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp))
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    remainingLabel,
                    modifier = Modifier.alignByBaseline().weight(1f, fill = false),
                    style = MaterialTheme.typography.displayLarge.copy(
                        fontFamily = PulseNumber,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Normal,
                        fontWeight = FontWeight.Normal,
                        fontSize = if (remainingMinutes < 1_000L) 83.sp else 56.sp,
                        lineHeight = if (remainingMinutes < 1_000L) 84.sp else 60.sp,
                        letterSpacing = (-3).sp,
                    ),
                    maxLines = 1,
                )
                Text("min", modifier = Modifier.alignByBaseline(), style = MaterialTheme.typography.bodyMedium.copy(fontFamily = PulseNumber, fontSize = 21.sp), color = androidx.compose.ui.graphics.Color(0xFF455922))
            }
            Spacer(Modifier.height(4.dp))
            PulseText("A little time. Well earned.", 11, color = androidx.compose.ui.graphics.Color(0xFF455922))
            Spacer(Modifier.height(22.dp))
            Box(Modifier.fillMaxWidth().height(6.dp).background(PulseCharcoal.copy(alpha = 0.16f), PillShape)) {
                Box(Modifier.fillMaxWidth(remainingFraction).height(6.dp).background(PulseCharcoal, PillShape))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                PulseText("${(budget?.usedMs ?: 0L) / 60_000} min used", 10, color = androidx.compose.ui.graphics.Color(0xFF455922))
                PulseText("${budget?.limitMinutes ?: 0L} min total", 10, color = androidx.compose.ui.graphics.Color(0xFF455922))
            }
        }
    }

}

@Composable
private fun MonthlyPushupHistory(snapshot: StateSnapshot) {
    SectionLabel("MONTHLY PUSH-UPS · 9TH TO 9TH")
    Spacer(Modifier.height(8.dp))
    Text(
        "${snapshot.currentMonthlyPushupCycle.totalPushups}",
        style = MaterialTheme.typography.headlineLarge.copy(fontFamily = PulseDisplay),
        color = EditorialTheme.colors.go,
    )
    Text(
        "push-ups this cycle · ${formatMonthlyCycleRange(snapshot.currentMonthlyPushupCycle)}",
        style = MaterialTheme.typography.bodySmall,
        color = EditorialTheme.colors.ink3,
    )
    Spacer(Modifier.height(14.dp))
    HorizontalDivider(color = EditorialTheme.colors.hairline)
    Spacer(Modifier.height(12.dp))
    SectionLabel("PREVIOUS CYCLES")
    if (snapshot.completedMonthlyPushupCycles.isEmpty()) {
        Spacer(Modifier.height(8.dp))
        Text(
            "No completed cycles yet",
            style = MaterialTheme.typography.bodySmall,
            color = EditorialTheme.colors.ink3,
        )
    } else {
        snapshot.completedMonthlyPushupCycles.forEach { cycle ->
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    formatMonthlyCycleRange(cycle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${cycle.totalPushups} push-ups",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }
}

@Composable
private fun DashboardHeader(onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH)).uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, letterSpacing = 1.4.sp))
            Spacer(Modifier.height(7.dp))
            Text("MAKE TIME.", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 35.sp, lineHeight = 40.sp, letterSpacing = (-1).sp))
        }
        Box(Modifier.size(34.dp).background(if(MaterialTheme.colorScheme.background == PaperLight) PulseCharcoal else CardDark, CircleShape).clip(CircleShape).clickable(onClick = onOpenSettings).semantics { contentDescription = "Open settings" }, contentAlignment = Alignment.Center) { PulseIcon("settings", 17, PulseYellow) }
    }
}

@Composable
private fun ProtectionLine(snapshot: StateSnapshot) {
    val ready = snapshot.enforcementHealth in setOf(ProtectionHealth.READY, ProtectionHealth.READY_LIMITED_RECOVERY)
    val color = if(ready) EditorialTheme.colors.accent else EditorialTheme.colors.low
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        PulseIcon("shield", 15, color)
        Text(if(ready) "PROTECTION IS ACTIVE" else "PROTECTION NEEDS ATTENTION", Modifier.weight(1f), color = color, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, letterSpacing = 1.sp))
        Box(Modifier.size(5.dp).background(color, CircleShape))
    }
}

@Composable
private fun WeekContent(snapshot: StateSnapshot) {
    val reports = snapshot.currentWeekUsageReports
    val totalUsage = reports.sumOf { it.protectedUsageMs }
    val totalSets = reports.sumOf { it.exerciseSets }
    SummaryTotals(totalUsage, totalSets, "browser time this week", "exercise sets this week")
    Spacer(Modifier.height(24.dp))
    SectionLabel("MONDAY – SUNDAY")
    Spacer(Modifier.height(8.dp))
    val maxUsage = reports.maxOfOrNull { it.protectedUsageMs }?.coerceAtLeast(1L) ?: 1L
    val todayKey = snapshot.todayUsageReport.localDayKey
    reports.forEachIndexed { index, report ->
        WeekDayRow(
            name = WEEKDAY_NAMES.getOrElse(index) { dayName(report.localDayKey) },
            report = report,
            maximumUsageMs = maxUsage,
            isToday = report.localDayKey == todayKey,
        )
    }
    DashboardFooter("Available today", availableToday(snapshot))
}

@Composable
private fun DayContent(snapshot: StateSnapshot) {
    val report = snapshot.todayUsageReport
    SummaryTotals(
        report.protectedUsageMs,
        report.exerciseSets,
        "browser time today",
        "exercise sets today",
    )
    Spacer(Modifier.height(24.dp))
    SectionLabel("TODAY'S ACTIVITY")
    Spacer(Modifier.height(8.dp))
    val events = snapshot.todayActivityEvents.sortedBy { it.startedAtWallMs }.map { event ->
        when (event.type) {
            ActivityEventType.EXERCISE_SET -> TimelineItem(
                title = "Exercise set",
                detail = formatEventTime(event.startedAtWallMs),
                value = "+${formatMinutes(event.valueMs)}",
                earned = true,
            )
            ActivityEventType.BROWSER_SESSION -> TimelineItem(
                title = "Browser session",
                detail = formatEventTime(event.startedAtWallMs),
                value = formatMinutes(event.valueMs),
                earned = false,
            )
        }
    }.ifEmpty {
        listOf(
            TimelineItem(
                title = "Exercise sets",
                detail = if (report.exerciseSets > 0) "Earlier today" else "None yet today",
                value = formatSets(report.exerciseSets),
                earned = true,
            ),
            TimelineItem(
                title = "Browser sessions",
                detail = if (report.protectedUsageMs > 0) "Earlier today" else "None yet today",
                value = formatReportTime(report.protectedUsageMs),
                earned = false,
            ),
        )
    }
    events.forEach { item ->
        ActivityTimelineRow(item)
    }
    DashboardFooter("Available now", availableToday(snapshot))
}

@Composable
private fun SummaryTotals(
    usageMs: Long,
    sets: Long,
    usageLabel: String,
    setsLabel: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SummaryTotal(
            value = formatReportTime(usageMs),
            label = usageLabel,
            accent = true,
            modifier = Modifier.weight(1f),
        )
        SummaryTotal(
            value = formatSets(sets),
            label = setsLabel,
            accent = false,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun SummaryTotal(value: String, label: String, accent: Boolean, modifier: Modifier) {
    Column(
        modifier = modifier.padding(bottom = 14.dp),
    ) {
        Text(
            value,
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = PulseDisplay),
            color = if (accent) EditorialTheme.colors.accentInk else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(7.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = EditorialTheme.colors.ink3)
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = EditorialTheme.colors.hairline)
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = EditorialTheme.colors.ink3)
}

@Composable
private fun WeekDayRow(
    name: String,
    report: DailyUsageReport,
    maximumUsageMs: Long,
    isToday: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(34.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            modifier = Modifier.width(34.dp),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
            color = if (isToday) MaterialTheme.colorScheme.onSurface else EditorialTheme.colors.ink3,
        )
        Box(
            Modifier
                .weight(1f)
                .height(5.dp)
                .background(EditorialTheme.colors.hairlineSoft, PillShape),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((report.protectedUsageMs.toFloat() / maximumUsageMs).coerceIn(0f, 1f))
                    .height(5.dp)
                    .background(EditorialTheme.colors.accent, PillShape),
            )
        }
        Text(
            formatReportTime(report.protectedUsageMs),
            modifier = Modifier.width(58.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (isToday) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        Text(
            formatSets(report.exerciseSets),
            modifier = Modifier.width(54.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (report.exerciseSets > 0) EditorialTheme.colors.go else EditorialTheme.colors.ink3,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

@Composable
private fun ActivityTimelineRow(item: TimelineItem) {
    val lineColor = EditorialTheme.colors.hairline
    val dotColor = if (item.earned) EditorialTheme.colors.go else EditorialTheme.colors.accent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 74.dp)
            .drawBehind {
                val x = 6.dp.toPx()
                val dotY = 27.dp.toPx()
                drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                drawCircle(dotColor, radius = 5.dp.toPx(), center = Offset(x, dotY))
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(30.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(item.detail, style = MaterialTheme.typography.bodySmall, color = EditorialTheme.colors.ink3)
        }
        Text(
            item.value,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DashboardFooter(label: String, available: String) {
    HorizontalDivider(
        modifier = Modifier.padding(top = 14.dp, bottom = 14.dp),
        color = EditorialTheme.colors.hairlineSoft,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = EditorialTheme.colors.ink3)
        Text(available, style = MaterialTheme.typography.labelMedium)
    }
}

private fun availableToday(snapshot: StateSnapshot): String = snapshot.budgetSnapshots
    .firstOrNull { it.enabled }
    ?.remainingMs
    ?.let(::formatReportTime)
    ?: "0m"

private fun formatSets(count: Long): String = "$count ${if (count == 1L) "set" else "sets"}"

private fun formatMinutes(ms: Long): String = if (ms < 60_000L) "<1 min" else {
    val minutes = (ms + 59_999L) / 60_000L
    "$minutes min"
}

private fun formatEventTime(wallMs: Long): String = Instant.ofEpochMilli(wallMs)
    .atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofPattern("HH:mm"))

internal fun carryExpirationRemainingMs(nowWallMs: Long, zoneId: ZoneId): Long {
    return (PeriodCalculator.nextDailyReset(nowWallMs, zoneId) - nowWallMs).coerceAtLeast(0L)
}

internal fun formatCarryExpiration(remainingMs: Long): String {
    val totalSeconds = (remainingMs.coerceAtLeast(0L) + 999L) / 1_000L
    val hours = totalSeconds / 3_600L
    val minutes = totalSeconds % 3_600L / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

internal fun formatMonthlyCycleRange(cycle: MonthlyPushupCycle): String {
    val start = LocalDate.parse(cycle.cycleStartDate)
    val end = LocalDate.parse(MonthlyPushupCalculator.nextCycleStart(cycle.cycleStartDate))
    val formatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    return "${start.format(formatter)} – ${end.format(formatter)}"
}

private data class TimelineItem(
    val title: String,
    val detail: String,
    val value: String,
    val earned: Boolean,
)

private fun dayName(key: String): String = runCatching {
    LocalDate.parse(key).dayOfWeek.name.take(3)
}.getOrDefault("—")

private val WEEKDAY_NAMES = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN")
