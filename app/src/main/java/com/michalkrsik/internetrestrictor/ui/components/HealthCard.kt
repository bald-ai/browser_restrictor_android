package com.michalkrsik.internetrestrictor.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.domain.ProtectionHealth

data class HealthCopy(
    val title: String,
    val body: String,
    val action: String?,
    val positive: Boolean,
)

fun healthCopy(
    health: ProtectionHealth,
    usageAccessGranted: Boolean = false,
): HealthCopy = when (health) {
    ProtectionHealth.READY -> HealthCopy(
        "Protection ready",
        "Chrome is protected and recovery access is available.",
        null,
        true,
    )
    ProtectionHealth.READY_LIMITED_RECOVERY -> if (usageAccessGranted) {
        HealthCopy(
            "Protection ready",
            "Usage access is enabled and the real-time Chrome gate is working. Recovery after a process interruption remains limited in this build.",
            null,
            true,
        )
    } else {
        HealthCopy(
            "Protected with limited recovery",
            "The real-time Chrome gate is working. A process interruption may leave a small usage gap uncounted.",
            "Improve recovery",
            true,
        )
    }
    ProtectionHealth.ENFORCEMENT_OFF -> HealthCopy(
        "Enforcement is off",
        "Limits and usage are kept, but Chrome is not currently gated or counted.",
        "Turn on",
        false,
    )
    ProtectionHealth.ACCESSIBILITY_REQUIRED -> HealthCopy(
        "Accessibility access required",
        "Internet Restrictor cannot protect Chrome until its service is enabled in Android Settings.",
        "Open Accessibility",
        false,
    )
    ProtectionHealth.USAGE_ACCESS_RECOMMENDED -> HealthCopy(
        "Usage access recommended",
        "Real-time protection works, but recovery after an interruption is limited.",
        "Open Usage Access",
        false,
    )
    ProtectionHealth.SERVICE_DISCONNECTED -> HealthCopy(
        "Protection service disconnected",
        "Android shows the service as enabled, but it is not running. Reopen Accessibility settings to repair it.",
        "Repair service",
        false,
    )
    ProtectionHealth.CHROME_NOT_FOUND -> HealthCopy(
        "Stable Chrome not found",
        "Version 1 protects only the stable Google Chrome package.",
        "Install or enable Chrome",
        false,
    )
    ProtectionHealth.STORAGE_ERROR -> HealthCopy(
        "Stored state needs repair",
        "Usage was not reset. Protection is visibly open until the local state can be trusted again.",
        "Open app info",
        false,
    )
    ProtectionHealth.RESTRICTED_SETTINGS_BLOCKED -> HealthCopy(
        "Restricted setting blocked",
        "Open app info, use the three-dot menu, choose Allow restricted settings, then return to Accessibility.",
        "Open app info",
        false,
    )
    ProtectionHealth.ADVANCED_PROTECTION_BLOCKED -> HealthCopy(
        "Advanced Protection blocks setup",
        "This device mode does not allow the non-accessibility-tool service, so Chrome cannot be protected here.",
        "Open Android help",
        false,
    )
}

@Composable
fun HealthCard(
    health: ProtectionHealth,
    usageAccessGranted: Boolean,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val copy = healthCopy(health, usageAccessGranted)
    val accent = if (copy.positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(if (copy.positive) "●" else "!", color = accent)
                Text(copy.title, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                copy.body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            copy.action?.let { label ->
                Button(onClick = onAction, shape = RoundedCornerShape(14.dp)) { Text(label) }
            }
        }
    }
}
