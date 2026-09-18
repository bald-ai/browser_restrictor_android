package com.michalkrsik.internetrestrictor.ui.settings

import com.michalkrsik.internetrestrictor.ui.theme.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.domain.MAX_SESSION_MINUTES
import com.michalkrsik.internetrestrictor.domain.MIN_SESSION_MINUTES
import com.michalkrsik.internetrestrictor.domain.ProtectionHealth
import com.michalkrsik.internetrestrictor.domain.RestrictorSettings
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.ui.components.HealthCard
import com.michalkrsik.internetrestrictor.ui.components.PermissionStateRows
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PulsePanel
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.pulseButtonColors
import com.michalkrsik.internetrestrictor.ui.theme.PulseActionBorder
import com.michalkrsik.internetrestrictor.ui.theme.editorialTitle

@Composable
fun SettingsScreen(
    snapshot: StateSnapshot,
    onBack: () -> Unit,
    onThemeChange: (ThemePreference) -> Unit,
    onSave: (RestrictorSettings) -> Unit,
    onCancelActiveUnlock: () -> Unit,
    onClearReports: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onHealthAction: () -> Unit,
    onExportDiagnostics: () -> Unit,
    onCustomize: () -> Unit = {},
) {
    BackHandler(onBack = onBack)
    var firstSession by rememberSaveable { mutableStateOf(snapshot.settings.sessionMinutes.getOrElse(0) { 3L }.toString()) }
    var secondSession by rememberSaveable { mutableStateOf(snapshot.settings.sessionMinutes.getOrElse(1) { 5L }.toString()) }
    var thirdSession by rememberSaveable { mutableStateOf(snapshot.settings.sessionMinutes.getOrElse(2) { 10L }.toString()) }
    var showClearReportsDialog by rememberSaveable { mutableStateOf(false) }
    var systemAccessExpanded by rememberSaveable { mutableStateOf(false) }
    var developerExpanded by rememberSaveable { mutableStateOf(false) }

    val parsedSessions = listOf(firstSession, secondSession, thirdSession).map(::parseSessionMinutes)
    val sessionValues = parsedSessions.filterNotNull()
    val sessionsValid = sessionValues.size == 3

    PulseScreen {
        PulseBack(onClick = onBack)
        Spacer(Modifier.height(23.dp))
        Text("YOUR PREFERENCES", style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.4.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(7.dp))
        Text("BROWSER RULES.", style = MaterialTheme.typography.headlineLarge.copy(fontSize = 35.sp, lineHeight = 40.sp, letterSpacing = (-1).sp))
        Spacer(Modifier.height(21.dp))
        if (com.michalkrsik.internetrestrictor.BuildConfig.IS_V1) {
            PulsePrimary("Customize tasks & rules", onCustomize, subtitle = "Settings, rewards, reset times and task definitions")
        } else PulseControlGroup {
            PulseSection("Session presets", "Minutes")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetField(firstSession, { firstSession = it }, 1, parsedSessions[0] == null, Modifier.weight(1f), snapshot.settings.theme == ThemePreference.LIGHT)
                PresetField(secondSession, { secondSession = it }, 2, parsedSessions[1] == null, Modifier.weight(1f), snapshot.settings.theme == ThemePreference.LIGHT)
                PresetField(thirdSession, { thirdSession = it }, 3, parsedSessions[2] == null, Modifier.weight(1f), snapshot.settings.theme == ThemePreference.LIGHT)
            }
            Spacer(Modifier.height(11.dp))
            Text("Choose the lengths offered when you open a browser. From 1 to 120 minutes.", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 17.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            if(!sessionsValid) PulseText("Use whole-minute values between 1 and 120.", 10, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(13.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PulseText("Dark appearance", 11, weight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                PulseText(if(snapshot.settings.theme == ThemePreference.DARK) "Dark appearance selected" else "Light appearance selected", 9, muted = true)
            }
            val dark = snapshot.settings.theme == ThemePreference.DARK
            Box(Modifier.size(32.dp, 19.dp).background(if(dark) PulseYellow else androidx.compose.ui.graphics.Color(0xFF7D8D50), RoundedCornerShape(50)).toggleable(value = dark, role = Role.Switch, onValueChange = { onThemeChange(if(it) ThemePreference.DARK else ThemePreference.LIGHT) }).semantics { stateDescription = if(dark) "On" else "Off"; contentDescription = "Dark appearance" }, contentAlignment = if(dark) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.padding(2.dp).size(15.dp).background(if(dark) androidx.compose.ui.graphics.Color.White else PaperLight, RoundedCornerShape(50)))
            }
        }
        HorizontalDivider(color = EditorialTheme.colors.hairline)
        Row(Modifier.fillMaxWidth().clickable { systemAccessExpanded = true }.padding(vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PulseText("Browser protection", 11, weight = FontWeight.Medium)
                Spacer(Modifier.height(4.dp))
                PulseText(if(snapshot.accessibilityEnabled) "All required access is enabled" else "Required access needs attention", 9, muted = true)
            }
            PulseIcon("shield", 18, EditorialTheme.colors.accent)
        }
        HorizontalDivider(color = EditorialTheme.colors.hairline)
        Row(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            PulseText("How time works", 13, weight = FontWeight.SemiBold)
            Box(Modifier.clickable { developerExpanded = true }.semantics { contentDescription = "More settings and diagnostics" }) { PulseIcon("help", 16, MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        listOf("Earned time" to "${snapshot.configuration.settings.minutesPerCredit} min / credit", "Focused pause" to "${snapshot.configuration.settings.waitSeconds} seconds", "Daily allowance" to "${snapshot.configuration.settings.dailyBudgetMinutes} min at reset", "Daily reset" to "Every day, ${snapshot.configuration.settings.dayReset}").forEach { (label, detail) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) { PulseText(label, 11, weight = FontWeight.Medium); PulseText(detail, 10, muted = true) }
            HorizontalDivider(color = EditorialTheme.colors.hairline)
        }
        Text("Tasks contribute toward your next credit. Emergency access is earned with your first credit of the day.", Modifier.padding(top = 11.dp), style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp, lineHeight = 17.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        if (!com.michalkrsik.internetrestrictor.BuildConfig.IS_V1) PulsePrimary("Save changes", { onSave(snapshot.settings.copy(delayEnabled = true, sessionMinutes = sessionValues)) }, enabled = sessionsValid)
        Spacer(Modifier.height(24.dp))
        Spacer(Modifier.weight(1f))
        PulseNav(settings = true, onToday = onBack, onSettings = {})
    }
    if(systemAccessExpanded || developerExpanded) {
        AlertDialog(onDismissRequest = { systemAccessExpanded = false; developerExpanded = false }, title = { Text(if(systemAccessExpanded) "Browser protection" else "Diagnostics and maintenance") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if(systemAccessExpanded) {
                    PermissionStateRows(snapshot, onOpenAccessibility, onOpenUsageAccess)
                    HealthCard(health = snapshot.enforcementHealth, usageAccessGranted = snapshot.usageAccessGranted, onAction = onHealthAction)
                } else {
                    GhostButton(onCancelActiveUnlock, enabled = snapshot.activeUnlock != null) { Text(if(snapshot.activeUnlock != null) "Cancel active unlock" else "No active unlock") }
                    TextButton(onClick = { showClearReportsDialog = true }) { Text("Clear report history") }
                    TextButton(onClick = onExportDiagnostics) { Text("Export diagnostics") }
                    PulseText("Exercise verifier model", 11, muted = true)
                    PulseText("Configured by the task verification backend", 11)
                }
            }
        }, confirmButton = { TextButton(onClick = { systemAccessExpanded = false; developerExpanded = false }) { Text("Done") } })
    }

    if (showClearReportsDialog) {
        AlertDialog(
            onDismissRequest = { showClearReportsDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Clear report history?") },
            text = { Text("This removes stored browser-time and exercise-set history. Today's budget and current restriction counters stay. Monthly push-up history is kept.") },
            confirmButton = {
                Button(onClick = {
                    showClearReportsDialog = false
                    onClearReports()
                }) { Text("Clear reports") }
            },
            dismissButton = {
                TextButton(onClick = { showClearReportsDialog = false }) {
                    Text("Cancel", color = EditorialTheme.colors.accent)
                }
            },
        )
    }
}

@Composable
private fun PresetField(value: String, onValueChange: (String) -> Unit, number: Int, isError: Boolean, modifier: Modifier, day: Boolean) {
    Column(modifier) {
        Text("PRESET $number", style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp, letterSpacing = .5.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(5.dp))
        BasicTextField(value, onValueChange, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), cursorBrush = SolidColor(PulseYellow), textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 23.sp, textAlign = TextAlign.Center, color = if(day) PulseYellow else InkDark), modifier = Modifier.fillMaxWidth().height(46.dp).background(if(day) Paper2Dark else PaperDark, RoundedCornerShape(8.dp)).border(1.dp, if(isError) MaterialTheme.colorScheme.error else EditorialTheme.colors.hairline, RoundedCornerShape(8.dp)).semantics { contentDescription = "Session preset $number in minutes" }, decorationBox = { inner -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { inner() } })
    }
}

@Composable
private fun GhostButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = PillShape,
        border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        content = content,
    )
}

private fun parseSessionMinutes(raw: String): Long? {
    if (raw.isBlank() || raw.any { !it.isDigit() }) return null
    return raw.toLongOrNull()?.takeIf { it in MIN_SESSION_MINUTES..MAX_SESSION_MINUTES }
}
