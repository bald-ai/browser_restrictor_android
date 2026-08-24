package com.michalkrsik.internetrestrictor.ui.settings

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
import com.michalkrsik.internetrestrictor.ui.challenge.PushupChallengeActivity
import com.michalkrsik.internetrestrictor.ui.components.HealthCard
import com.michalkrsik.internetrestrictor.ui.components.PermissionStateRows
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
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
) {
    BackHandler(onBack = onBack)
    var firstSession by rememberSaveable { mutableStateOf(snapshot.settings.sessionMinutes[0].toString()) }
    var secondSession by rememberSaveable { mutableStateOf(snapshot.settings.sessionMinutes[1].toString()) }
    var thirdSession by rememberSaveable { mutableStateOf(snapshot.settings.sessionMinutes[2].toString()) }
    var showClearReportsDialog by rememberSaveable { mutableStateOf(false) }
    var systemAccessExpanded by rememberSaveable { mutableStateOf(false) }
    var developerExpanded by rememberSaveable { mutableStateOf(false) }

    val parsedSessions = listOf(firstSession, secondSession, thirdSession).map(::parseSessionMinutes)
    val sessionValues = parsedSessions.filterNotNull()
    val sessionsValid = sessionValues.size == 3

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("‹  Dashboard", color = EditorialTheme.colors.accent)
        }
        Text(
            "SETTINGS",
            style = MaterialTheme.typography.labelSmall,
            color = EditorialTheme.colors.ink3,
        )
        Text(editorialTitle("Browser rules."), style = MaterialTheme.typography.headlineLarge)

        SettingsCard("Appearance") {
            ToggleRow(
                title = "Dark theme",
                supporting = "The app starts in dark theme by default.",
                checked = snapshot.settings.theme == ThemePreference.DARK,
                onCheckedChange = {
                    onThemeChange(if (it) ThemePreference.DARK else ThemePreference.LIGHT)
                },
            )
        }

        SettingsCard("Session presets") {
            Text(
                "Choose the three session lengths shown on the browser gate. Each value can be from 1 minute to 2 hours.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditorialField(
                    value = firstSession,
                    onValueChange = { firstSession = it },
                    label = { Text("Preset 1") },
                    isError = parsedSessions[0] == null,
                    supportingText = null,
                    modifier = Modifier.weight(1f),
                )
                EditorialField(
                    value = secondSession,
                    onValueChange = { secondSession = it },
                    label = { Text("Preset 2") },
                    isError = parsedSessions[1] == null,
                    supportingText = null,
                    modifier = Modifier.weight(1f),
                )
                EditorialField(
                    value = thirdSession,
                    onValueChange = { thirdSession = it },
                    label = { Text("Preset 3") },
                    isError = parsedSessions[2] == null,
                    supportingText = null,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!sessionsValid) {
                Text(
                    "Use whole-minute values between 1 and 120.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        ExpandableSettingsSection(
            title = "System access",
            summary = buildString {
                append(if (snapshot.accessibilityEnabled) "Accessibility on" else "Accessibility needs attention")
                append(" · ")
                append(if (snapshot.usageAccessGranted) "Usage Access on" else "Usage Access off")
            },
            expanded = systemAccessExpanded,
            onToggle = { systemAccessExpanded = !systemAccessExpanded },
        ) {
            PermissionStateRows(
                snapshot = snapshot,
                onOpenAccessibility = onOpenAccessibility,
                onOpenUsageAccess = onOpenUsageAccess,
            )
        }

        if (snapshot.enforcementHealth !in permissionHealthStates) {
            HealthCard(
                health = snapshot.enforcementHealth,
                usageAccessGranted = snapshot.usageAccessGranted,
                onAction = onHealthAction,
            )
        }

        ExpandableSettingsSection(
            title = "Developer",
            summary = "Diagnostics and maintenance",
            expanded = developerExpanded,
            onToggle = { developerExpanded = !developerExpanded },
        ) {
            Text(
                "Cancels only the currently granted browser session. Usage totals, budgets, skips, reports, and settings stay unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GhostButton(
                onClick = onCancelActiveUnlock,
                enabled = snapshot.activeUnlock != null,
            ) {
                Text(if (snapshot.activeUnlock != null) "Cancel active unlock" else "No active unlock")
            }
            HorizontalDivider(color = EditorialTheme.colors.hairlineSoft)
            Text(
                "Deletes the stored browser-time and push-up history used by reports. Today's active budget counters remain unchanged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showClearReportsDialog = true }) {
                Text("Clear report history", color = EditorialTheme.colors.accent)
            }
            OutlinedButton(
                onClick = onExportDiagnostics,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = PillShape,
                border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
            ) { Text("Export diagnostics") }
            HorizontalDivider(color = EditorialTheme.colors.hairlineSoft)
            Text(
                "Push-up verifier model",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                PushupChallengeActivity.MODEL,
                style = MaterialTheme.typography.labelMedium,
                color = EditorialTheme.colors.accent,
            )
        }

        Button(
            onClick = {
                onSave(
                    snapshot.settings.copy(
                        delayEnabled = true,
                        sessionMinutes = sessionValues,
                    ),
                )
            },
            enabled = sessionsValid,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = PillShape,
        ) { Text("Save changes") }
        Spacer(Modifier.height(12.dp))
    }

    if (showClearReportsDialog) {
        AlertDialog(
            onDismissRequest = { showClearReportsDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Clear report history?") },
            text = { Text("This removes stored browser-time and push-up history. Today's budget and current restriction counters stay.") },
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

@Composable
private fun EditorialField(
    value: String,
    onValueChange: (String) -> Unit,
    label: @Composable () -> Unit,
    isError: Boolean,
    supportingText: @Composable (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        singleLine = true,
        isError = isError,
        supportingText = supportingText,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = EditorialTheme.colors.accent,
            unfocusedBorderColor = EditorialTheme.colors.hairline,
            cursorColor = EditorialTheme.colors.accent,
            focusedLabelColor = EditorialTheme.colors.accentInk,
        ),
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = EditorialTheme.colors.hairline)
        Column(
            Modifier.padding(top = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            content()
        }
    }
}

@Composable
private fun ExpandableSettingsSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = EditorialTheme.colors.hairline)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onToggle,
                )
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                if (expanded) "Hide" else "Show",
                modifier = Modifier
                    .padding(start = 12.dp)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                color = EditorialTheme.colors.accent,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(15.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.surface,
                checkedTrackColor = EditorialTheme.colors.go,
                checkedBorderColor = EditorialTheme.colors.go,
                uncheckedThumbColor = MaterialTheme.colorScheme.surface,
                uncheckedTrackColor = EditorialTheme.colors.hairline,
                uncheckedBorderColor = EditorialTheme.colors.hairline,
            ),
        )
    }
}

private fun parseSessionMinutes(raw: String): Long? {
    if (raw.isBlank() || raw.any { !it.isDigit() }) return null
    return raw.toLongOrNull()?.takeIf { it in MIN_SESSION_MINUTES..MAX_SESSION_MINUTES }
}

private val permissionHealthStates = setOf(
    ProtectionHealth.READY,
    ProtectionHealth.READY_LIMITED_RECOVERY,
    ProtectionHealth.ACCESSIBILITY_REQUIRED,
    ProtectionHealth.USAGE_ACCESS_RECOMMENDED,
    ProtectionHealth.SERVICE_DISCONNECTED,
)
