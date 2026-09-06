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
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape

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
        "Supported browsers and X are protected, and recovery access is available.",
        null,
        true,
    )
    ProtectionHealth.READY_LIMITED_RECOVERY -> if (usageAccessGranted) {
        HealthCopy(
            "Protection ready",
            "Usage access is enabled and the real-time browser and X gate is working. Recovery after a process interruption remains limited in this build.",
            null,
            true,
        )
    } else {
        HealthCopy(
            "Protected with limited recovery",
            "The real-time browser and X gate is working. A process interruption may leave a small usage gap uncounted.",
            "Improve recovery",
            true,
        )
    }
    ProtectionHealth.ENFORCEMENT_OFF -> HealthCopy(
        "Enforcement is off",
        "Browsers and X are not gated, but their active use is still counted in your report.",
        "Turn on",
        false,
    )
    ProtectionHealth.ACCESSIBILITY_REQUIRED -> HealthCopy(
        "Accessibility access required",
        "Internet Restrictor cannot protect browsers or X until its service is enabled in Android Settings.",
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
    ProtectionHealth.BROWSERS_NOT_FOUND -> HealthCopy(
        "No supported app found",
        "Install or enable Chrome, Firefox, Edge, Opera, or X.",
        "Get a supported app",
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
        "This device mode does not allow the non-accessibility-tool service, so browsers and X cannot be protected here.",
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
    compact: Boolean = false,
) {
    val copy = healthCopy(health, usageAccessGranted)
    val accent = if (copy.positive) EditorialTheme.colors.go else EditorialTheme.colors.low
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    ) {
        if (compact) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (copy.positive) "●" else "!", color = accent)
                Text(copy.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                copy.action?.let { label ->
                    Button(onClick = onAction, shape = PillShape) { Text(label) }
                }
            }
        } else Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
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
                Button(onClick = onAction, shape = PillShape) { Text(label) }
            }
        }
    }
}
