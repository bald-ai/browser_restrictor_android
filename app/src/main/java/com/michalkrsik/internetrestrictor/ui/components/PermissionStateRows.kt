package com.michalkrsik.internetrestrictor.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape

@Composable
fun PermissionStateRows(
    snapshot: StateSnapshot,
    onOpenAccessibility: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accessibility = when {
        !snapshot.accessibilityEnabled -> PermissionRowState(
            status = "Not enabled",
            color = EditorialTheme.colors.low,
            action = "Enable",
        )
        !snapshot.serviceConnected -> PermissionRowState(
            status = "Service disconnected",
            color = EditorialTheme.colors.low,
            action = "Repair",
        )
        else -> PermissionRowState(
            status = "Ready",
            color = EditorialTheme.colors.go,
            action = null,
        )
    }
    val usage = if (snapshot.usageAccessGranted) {
        PermissionRowState("Ready", EditorialTheme.colors.go, null)
    } else {
        PermissionRowState("Not enabled", EditorialTheme.colors.warn, "Enable")
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PermissionRow(
            role = "REQUIRED",
            title = "Accessibility",
            purpose = "Runs the real-time browser gate and measures focused browser use.",
            state = accessibility,
            onAction = onOpenAccessibility,
        )
        PermissionRow(
            role = "RECOMMENDED",
            title = "Usage Access",
            purpose = "Improves recovery after a brief service or process interruption.",
            state = usage,
            onAction = onOpenUsageAccess,
        )
    }
}

private data class PermissionRowState(
    val status: String,
    val color: Color,
    val action: String?,
)

@Composable
private fun PermissionRow(
    role: String,
    title: String,
    purpose: String,
    state: PermissionRowState,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, EditorialTheme.colors.hairlineSoft),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).background(state.color, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(role, style = MaterialTheme.typography.labelSmall, color = EditorialTheme.colors.ink3)
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    purpose,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(state.status, style = MaterialTheme.typography.labelMedium, color = state.color)
            }
            state.action?.let { label ->
                Button(onClick = onAction, shape = PillShape) { Text(label) }
            }
        }
    }
}
