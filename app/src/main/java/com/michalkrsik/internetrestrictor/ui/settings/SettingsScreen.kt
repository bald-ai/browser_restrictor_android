package com.michalkrsik.internetrestrictor.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import com.michalkrsik.internetrestrictor.domain.MAX_SAFE_MINUTES
import com.michalkrsik.internetrestrictor.domain.RestrictorSettings
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.ThemePreference
import com.michalkrsik.internetrestrictor.ui.components.HealthCard

@Composable
fun SettingsScreen(
    snapshot: StateSnapshot,
    onBack: () -> Unit,
    onThemeChange: (ThemePreference) -> Unit,
    onPauseProtection: (Long?) -> Unit,
    onResumeProtection: () -> Unit,
    onSave: (RestrictorSettings) -> Unit,
    onResetUsage: () -> Unit,
    onClearReports: () -> Unit,
    onHealthAction: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    BackHandler(onBack = onBack)
    var dailyEnabled by rememberSaveable { mutableStateOf(snapshot.settings.dailyBudgetEnabled) }
    var dailyMinutes by rememberSaveable { mutableStateOf(snapshot.settings.dailyBudgetMinutes.toString()) }
    var weeklyEnabled by rememberSaveable { mutableStateOf(snapshot.settings.weeklyBudgetEnabled) }
    var weeklyMinutes by rememberSaveable { mutableStateOf(snapshot.settings.weeklyBudgetMinutes.toString()) }
    var monthlyEnabled by rememberSaveable { mutableStateOf(snapshot.settings.monthlyBudgetEnabled) }
    var monthlyMinutes by rememberSaveable { mutableStateOf(snapshot.settings.monthlyBudgetMinutes.toString()) }
    var delayEnabled by rememberSaveable { mutableStateOf(snapshot.settings.delayEnabled) }
    var skipsEnabled by rememberSaveable { mutableStateOf(snapshot.settings.emergencySkipsEnabled) }
    var skipLimit by rememberSaveable { mutableStateOf(snapshot.settings.emergencySkipDailyLimit.toString()) }
    var showResetDialog by rememberSaveable { mutableStateOf(false) }
    var showClearReportsDialog by rememberSaveable { mutableStateOf(false) }

    val daily = parseWholeNumber(dailyMinutes)
    val weekly = parseWholeNumber(weeklyMinutes)
    val monthly = parseWholeNumber(monthlyMinutes)
    val skips = parseWholeNumber(skipLimit)
    val valid = daily != null && weekly != null && monthly != null && skips != null

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹  Dashboard") }
        Text(
            "SETTINGS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.5.sp,
        )
        Text("Chrome rules.", style = MaterialTheme.typography.headlineLarge)

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
                "Clears all usage, the current session, waiting progress, daily skips, and today's unlock-cost count. Rules and theme stay.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = { showResetDialog = true }, shape = RoundedCornerShape(14.dp)) {
                Text("Reset usage")
            }
            Text(
                "Your 30-day report is kept when restriction counters are reset.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { showClearReportsDialog = true }) {
                Text("Clear report history")
            }
        }

        SettingsCard("Enforcement") {
            Text(
                if (snapshot.settings.enforcementEnabled) "Chrome protection is on." else
                    "Protection is off. Active Chrome use is still added to your report.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (snapshot.settings.enforcementEnabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(1L, 2L, 4L).forEach { hours ->
                        OutlinedButton(
                            onClick = { onPauseProtection(hours) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                        ) { Text("${hours}h") }
                    }
                }
                TextButton(onClick = { onPauseProtection(null) }) {
                    Text("Keep off until the daily auto-enable")
                }
            } else {
                Button(
                    onClick = onResumeProtection,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("Resume protection") }
            }
            Text(
                "Protection automatically turns on every day at 12:00 noon in your current timezone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Budgets") {
            BudgetInput("Daily", dailyEnabled, { dailyEnabled = it }, dailyMinutes, { dailyMinutes = it })
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            BudgetInput("Weekly", weeklyEnabled, { weeklyEnabled = it }, weeklyMinutes, { weeklyMinutes = it })
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
            BudgetInput("Monthly", monthlyEnabled, { monthlyEnabled = it }, monthlyMinutes, { monthlyMinutes = it })
            Text(
                "Disabled periods still accumulate usage, so re-enabling a limit respects the current period's total.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SettingsCard("Chrome access") {
            ToggleRow(
                title = "Require wait before Chrome access",
                supporting = "When off, Chrome opens directly but budgets and active-use accounting remain active.",
                checked = delayEnabled,
                onCheckedChange = { delayEnabled = it },
            )
        }

        SettingsCard("Emergency skips") {
            ToggleRow(
                title = "Enable skips",
                supporting = "Skips bypass only the focused wait. They never bypass an exhausted budget.",
                checked = skipsEnabled,
                onCheckedChange = { skipsEnabled = it },
            )
            OutlinedTextField(
                value = skipLimit,
                onValueChange = { skipLimit = it },
                label = { Text("Daily wait skips") },
                singleLine = true,
                isError = skips == null,
                supportingText = if (skips == null) ({ Text("Enter a whole number, zero or greater.") }) else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
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
                        dailyBudgetEnabled = dailyEnabled,
                        dailyBudgetMinutes = daily!!,
                        weeklyBudgetEnabled = weeklyEnabled,
                        weeklyBudgetMinutes = weekly!!,
                        monthlyBudgetEnabled = monthlyEnabled,
                        monthlyBudgetMinutes = monthly!!,
                        delayEnabled = delayEnabled,
                        emergencySkipsEnabled = skipsEnabled,
                        emergencySkipDailyLimit = skips!!,
                    ),
                )
            },
            enabled = valid,
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Save changes") }

        OutlinedButton(
            onClick = onExportDiagnostics,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp),
        ) { Text("Export diagnostics") }
        Spacer(Modifier.height(12.dp))
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset all usage?") },
            text = {
                Text("This also removes the current Chrome session and any waiting progress. Your limits, delay setting, skip limit, and theme remain.")
            },
            confirmButton = {
                Button(onClick = {
                    showResetDialog = false
                    onResetUsage()
                }) { Text("Reset usage") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }

    if (showClearReportsDialog) {
        AlertDialog(
            onDismissRequest = { showClearReportsDialog = false },
            title = { Text("Clear report history?") },
            text = { Text("This removes the protected/off usage history. Your limits and current restriction counters stay.") },
            confirmButton = {
                Button(onClick = {
                    showClearReportsDialog = false
                    onClearReports()
                }) { Text("Clear reports") }
            },
            dismissButton = {
                TextButton(onClick = { showClearReportsDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Column(
            Modifier.padding(top = 16.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(15.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BudgetInput(
    title: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
) {
    val parsed = parseWholeNumber(value)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ToggleRow(
            title = "$title budget",
            supporting = if (enabled) "Blocks Chrome when this period reaches its limit." else "Does not block; usage still accumulates.",
            checked = enabled,
            onCheckedChange = onEnabledChange,
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text("Minutes") },
            suffix = { Text("min") },
            singleLine = true,
            isError = parsed == null,
            supportingText = if (parsed == null) ({ Text("Enter a whole number, zero or greater.") }) else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun parseWholeNumber(raw: String): Long? {
    if (raw.isBlank() || raw.any { !it.isDigit() }) return null
    return raw.toLongOrNull()?.takeIf { it in 0..MAX_SAFE_MINUTES }
}
