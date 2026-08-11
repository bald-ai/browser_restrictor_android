package com.michalkrsik.internetrestrictor.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.domain.MAX_SAFE_MINUTES
import com.michalkrsik.internetrestrictor.domain.RestrictorSettings
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.ui.components.HealthCard
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.SerifNote
import com.michalkrsik.internetrestrictor.ui.theme.editorialTitle

@Composable
fun SettingsScreen(
    snapshot: StateSnapshot,
    onBack: () -> Unit,
    onThemeChange: (ThemePreference) -> Unit,
    onPauseProtection: (Long?) -> Unit,
    onResumeProtection: () -> Unit,
    onSave: (RestrictorSettings) -> Unit,
    onResetUsage: () -> Unit,
    onCancelActiveUnlock: () -> Unit,
    onClearReports: () -> Unit,
    onHealthAction: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var delayEnabled by rememberSaveable { mutableStateOf(snapshot.settings.delayEnabled) }
    var skipsEnabled by rememberSaveable { mutableStateOf(snapshot.settings.emergencySkipsEnabled) }
    var skipLimit by rememberSaveable { mutableStateOf(snapshot.settings.emergencySkipDailyLimit.toString()) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showClearReportsDialog by rememberSaveable { mutableStateOf(false) }

    val skips = parseWholeNumber(skipLimit)
    val valid = skips != null

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

        SettingsCard("Reset usage") {
            Text(
                "Clears today's usage and earned budget, the current session, waiting progress, and daily skips. Rules and theme stay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GhostButton(onClick = { showResetDialog = true }) {
                Text("Reset usage")
            }
            Text(
                "Your 30-day report is kept when restriction counters are reset.",
                style = SerifNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showClearReportsDialog = true }) {
                Text("Clear report history", color = EditorialTheme.colors.accent)
            }
        }

        SettingsCard("Developer controls") {
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
        }

        SettingsCard("Enforcement") {
            Text(
                if (snapshot.settings.enforcementEnabled) "Browser protection is on." else
                    "Protection is off. Active browser use is still added to your report.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.settings.enforcementEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1L, 2L, 4L).forEach { hours ->
                        GhostButton(
                            onClick = { onPauseProtection(hours) },
                            modifier = Modifier.weight(1f),
                        ) { Text("${hours}h") }
                    }
                }
                TextButton(onClick = { onPauseProtection(null) }) {
                    Text("Keep off until the daily auto-enable", color = EditorialTheme.colors.accent)
                }
            } else {
                Button(
                    onClick = onResumeProtection,
                    modifier = Modifier.fillMaxWidth(),
                    shape = PillShape,
                ) { Text("Resume protection") }
            }
            Text(
                "Protection automatically turns on every day at 12:00 noon in your current timezone.",
                style = SerifNote,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Budgets") {
            Text(
                "Every local day starts with 30 minutes for supported browsers. Each verified five-push-up round adds another 30 minutes to today's shared budget. There are no weekly or monthly pools.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Browser access") {
            ToggleRow(
                title = "Require wait before browser access",
                supporting = "When off, supported browsers open directly but budgets and active-use accounting remain active.",
                checked = delayEnabled,
                onCheckedChange = { delayEnabled = it },
            )
        }

        SettingsCard("Emergency skips") {
            ToggleRow(
                title = "Enable skips",
                supporting = "Skips open supported browsers for 30 minutes, including after the shared budget is exhausted.",
                checked = skipsEnabled,
                onCheckedChange = { skipsEnabled = it },
            )
            EditorialField(
                value = skipLimit,
                onValueChange = { skipLimit = it },
                label = { Text("Daily wait skips") },
                isError = skips == null,
                supportingText = if (skips == null) ({ Text("Enter a whole number, zero or greater.") }) else null,
            )
        }

        HealthCard(
            health = snapshot.enforcementHealth,
            usageAccessGranted = snapshot.usageAccessGranted,
            onAction = onHealthAction,
        )

        Button(
            onClick = {
                onSave(
                    snapshot.settings.copy(
                        delayEnabled = delayEnabled,
                        emergencySkipsEnabled = skipsEnabled,
                        emergencySkipDailyLimit = skips!!,
                    ),
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = PillShape,
        ) { Text("Save changes") }

        OutlinedButton(
            onClick = onExportDiagnostics,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = PillShape,
            border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) { Text("Export diagnostics") }
        Spacer(Modifier.height(12.dp))
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Reset all usage?") },
            text = {
                Text("This also removes today's earned budget, the current browser session, and any waiting progress. Your delay setting, skip limit, and theme remain.")
            },
            confirmButton = {
                Button(onClick = {
                    showResetDialog = false
                    onResetUsage()
                }) { Text("Reset usage") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("Cancel", color = EditorialTheme.colors.accent)
                }
            },
        )
    }

    if (showClearReportsDialog) {
        AlertDialog(
            onDismissRequest = { showClearReportsDialog = false },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Clear report history?") },
            text = { Text("This removes the protected/off usage history. Today's budget and current restriction counters stay.") },
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
        modifier = Modifier.fillMaxWidth(),
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

private fun parseWholeNumber(raw: String): Long? {
    if (raw.isBlank() || raw.any { !it.isDigit() }) return null
    return raw.toLongOrNull()?.takeIf { it in 0..MAX_SAFE_MINUTES }
}
