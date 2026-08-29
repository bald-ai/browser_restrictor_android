package com.michalkrsik.internetrestrictor.ui.dashboard

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
import com.michalkrsik.internetrestrictor.domain.MACHINE_REPS_PER_SET
import com.michalkrsik.internetrestrictor.domain.MonthlyPushupCalculator
import com.michalkrsik.internetrestrictor.domain.MonthlyPushupCycle
import com.michalkrsik.internetrestrictor.domain.PLANK_SECONDS_PER_SET
import com.michalkrsik.internetrestrictor.domain.PUSHUPS_PER_SET
import com.michalkrsik.internetrestrictor.domain.ProtectionHealth
import com.michalkrsik.internetrestrictor.domain.PeriodCalculator
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.ui.components.HealthCard
import com.michalkrsik.internetrestrictor.ui.components.formatReportTime
import com.michalkrsik.internetrestrictor.ui.components.healthCopy
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.InstrumentSerif
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

private enum class ActivityPeriod { DAY, WEEK }

@Composable
fun DashboardScreen(
    snapshot: StateSnapshot,
    onOpenSettings: () -> Unit,
    onTestBrowser: () -> Unit,
    onStartExercise: () -> Unit,
    onHealthAction: () -> Unit,
) {
    var period by rememberSaveable { mutableStateOf(ActivityPeriod.WEEK) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 22.dp),
    ) {
        DashboardHeader(onOpenSettings)
        Spacer(Modifier.height(26.dp))
        ProtectionLine(snapshot)
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                if (period == ActivityPeriod.WEEK) "Your week." else "Today.",
                style = MaterialTheme.typography.headlineLarge,
            )
            PeriodSwitch(period, onChange = { period = it })
        }

        val health = healthCopy(snapshot.enforcementHealth, snapshot.usageAccessGranted)
        if (!health.positive || health.action != null) {
            Spacer(Modifier.height(18.dp))
            HealthCard(
                health = snapshot.enforcementHealth,
                usageAccessGranted = snapshot.usageAccessGranted,
                onAction = onHealthAction,
                compact = true,
            )
        }

        Spacer(Modifier.height(18.dp))
        CarryOverStatus(snapshot)
        Spacer(Modifier.height(27.dp))
        MonthlyPushupHistory(snapshot)
        Spacer(Modifier.height(27.dp))
        if (period == ActivityPeriod.WEEK) {
            WeekContent(snapshot)
        } else {
            DayContent(snapshot)
        }

        Spacer(Modifier.height(22.dp))
        Button(
            onClick = onStartExercise,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
        ) {
            Text("Start exercise")
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "Test browser gate",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(PillShape)
                .clickable(enabled = snapshot.supportedBrowserInstalled, onClick = onTestBrowser)
                .padding(horizontal = 20.dp, vertical = 11.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (snapshot.supportedBrowserInstalled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                EditorialTheme.colors.ink3
            },
        )
    }
}

@Composable
private fun MonthlyPushupHistory(snapshot: StateSnapshot) {
    SectionLabel("MONTHLY PUSH-UPS · 9TH TO 9TH")
    Spacer(Modifier.height(8.dp))
    Text(
        "${snapshot.currentMonthlyPushupCycle.totalPushups}",
        style = MaterialTheme.typography.headlineLarge.copy(fontFamily = InstrumentSerif),
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
private fun CarryOverStatus(snapshot: StateSnapshot) {
    val nowWallMs by produceState(
        initialValue = snapshot.wallNowMs,
        key1 = snapshot.todayUsageReport.localDayKey,
    ) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val expiresIn = carryExpirationRemainingMs(nowWallMs, ZoneId.systemDefault())
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "EXERCISE CARRY-OVER",
                style = MaterialTheme.typography.labelSmall,
                color = EditorialTheme.colors.ink3,
            )
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "EXPIRES IN",
                    style = MaterialTheme.typography.labelSmall,
                    color = EditorialTheme.colors.ink3,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    formatCarryExpiration(expiresIn),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        CarryOverRow("${snapshot.pushupRepetitionRemainderToday}/$PUSHUPS_PER_SET push-ups")
        CarryOverRow("${snapshot.plankSecondRemainderToday}/${PLANK_SECONDS_PER_SET}s plank")
        CarryOverRow("${snapshot.machineRepetitionRemainderToday}/$MACHINE_REPS_PER_SET machine reps")
    }
    HorizontalDivider(
        modifier = Modifier.padding(top = 14.dp),
        color = EditorialTheme.colors.hairlineSoft,
    )
}

@Composable
private fun CarryOverRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun DashboardHeader(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "BROWSER RESTRICTOR",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .size(56.dp)
                .clickable(onClick = onOpenSettings)
                .semantics { contentDescription = "Open settings" },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .border(BorderStroke(1.dp, EditorialTheme.colors.hairlineSoft), CircleShape)
                    .clip(CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProtectionLine(snapshot: StateSnapshot) {
    val ready = snapshot.enforcementHealth in setOf(
        ProtectionHealth.READY,
        ProtectionHealth.READY_LIMITED_RECOVERY,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(
                    if (ready) EditorialTheme.colors.go else EditorialTheme.colors.low,
                    CircleShape,
                ),
        )
        Text(
            if (ready) "Protection is on" else "Protection needs attention",
            style = MaterialTheme.typography.labelMedium,
            color = if (ready) EditorialTheme.colors.go else EditorialTheme.colors.low,
        )
    }
}

@Composable
private fun PeriodSwitch(period: ActivityPeriod, onChange: (ActivityPeriod) -> Unit) {
    Row(
        modifier = Modifier
            .border(BorderStroke(1.dp, EditorialTheme.colors.hairlineSoft), PillShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        PeriodChoice("Day", ActivityPeriod.DAY, period, onChange)
        PeriodChoice("Week", ActivityPeriod.WEEK, period, onChange)
    }
}

@Composable
private fun PeriodChoice(
    label: String,
    choice: ActivityPeriod,
    selected: ActivityPeriod,
    onChange: (ActivityPeriod) -> Unit,
) {
    val active = choice == selected
    Box(
        modifier = Modifier
            .clip(PillShape)
            .background(if (active) MaterialTheme.colorScheme.onSurface else androidx.compose.ui.graphics.Color.Transparent)
            .clickable { onChange(choice) }
            .padding(horizontal = 13.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp),
            color = if (active) MaterialTheme.colorScheme.background else EditorialTheme.colors.ink3,
        )
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
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = InstrumentSerif),
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
