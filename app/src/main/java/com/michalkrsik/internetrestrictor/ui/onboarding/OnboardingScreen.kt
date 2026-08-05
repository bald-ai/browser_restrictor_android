package com.michalkrsik.internetrestrictor.ui.onboarding

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.ui.components.PauseEmblem
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.editorialTitle

@Composable
fun OnboardingScreen(
    snapshot: StateSnapshot,
    onOpenAccessibility: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onTestChrome: () -> Unit,
    onComplete: () -> Unit,
) {
    var page by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Text(
            "SETUP  ${page + 1} / 5",
            style = MaterialTheme.typography.labelSmall,
            color = EditorialTheme.colors.ink3,
        )
        when (page) {
            0 -> WelcomeStep()
            1 -> DefaultsStep()
            2 -> AccessibilityStep(
                enabled = snapshot.accessibilityEnabled,
                onOpenAccessibility = onOpenAccessibility,
                onOpenAppInfo = onOpenAppInfo,
            )
            3 -> UsageStep(
                granted = snapshot.usageAccessGranted,
                onOpenUsageAccess = onOpenUsageAccess,
            )
            else -> ReadyStep(snapshot, onTestChrome)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (page > 0) {
                OutlinedButton(
                    onClick = { page-- },
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = PillShape,
                    border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text("Back") }
            }
            Button(
                onClick = { if (page < 4) page++ else onComplete() },
                enabled = page != 2 || snapshot.accessibilityEnabled,
                modifier = Modifier.weight(1f).height(52.dp),
                shape = PillShape,
            ) {
                Text(if (page == 4) "Finish setup" else "Continue")
            }
        }
    }
}

@Composable
private fun WelcomeStep() {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PauseEmblem()
        Text(editorialTitle("Pause before Chrome."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Choose a session, wait briefly, and keep Chrome within your limits.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard(
            "Private by default",
            "Your data stays on this device. No account, ads, or analytics.",
        )
        InfoCard(
            "You stay in control",
            "Turn protection off, disable Accessibility, or use another browser anytime.",
        )
    }
}

@Composable
private fun DefaultsStep() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Start with simple limits."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Change them anytime.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard("Daily budget", "60 minutes")
        InfoCard("Weekly budget", "10 hours")
        InfoCard("Monthly budget", "40 hours")
        InfoCard("Sessions", "Choose 10, 30, or 60 minutes. First wait: 5 seconds per 10 minutes.")
        InfoCard("Emergency skips", "Three per day. Each opens Chrome for 30 minutes, even after a limit is used.")
    }
}

@Composable
private fun AccessibilityStep(
    enabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Enable the Chrome gate."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Accessibility detects Chrome, shows the gate, and counts active use.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DisclosureCard()
        Button(
            onClick = onOpenAccessibility,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
        ) { Text(if (enabled) "Accessibility enabled ✓" else "Open Accessibility settings") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InfoCard(
                "Switch blocked?",
                "In App info, choose Allow restricted settings, then return here.",
                actionLabel = "Open app info",
                onAction = onOpenAppInfo,
            )
        }
        if (!enabled) {
            Text(
                "Continue after Android confirms Accessibility is enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DisclosureCard() {
    val accent = EditorialTheme.colors.accent
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = accent.copy(alpha = 0.10f),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Why it is needed", style = MaterialTheme.typography.titleMedium)
            listOf(
                "Detects when stable Chrome is in front.",
                "Shows the pause screen.",
                "Counts active Chrome time.",
                "Never reads pages, URLs, typing, or screenshots.",
                "Keeps data on this device.",
            ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun UsageStep(granted: Boolean, onOpenUsageAccess: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Improve recovery."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Usage Access can reduce tracking gaps after an interruption. It never controls the gate.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard(
            "Accessibility stays in charge",
            "Uncertain time stays uncounted.",
        )
        Button(
            onClick = onOpenUsageAccess,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
        ) { Text(if (granted) "Usage Access granted ✓" else "Open Usage Access settings") }
        Text(
            if (granted) {
                "Granted. Recovery after interruptions is still limited in this build."
            } else {
                "Optional. Without it, recovery after interruptions is limited."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyStep(snapshot: StateSnapshot, onTestChrome: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Test the gate."), style = MaterialTheme.typography.headlineLarge)
        Text(
            if (snapshot.serviceConnected) {
                "Open Chrome. The pause screen should appear over it."
            } else {
                "The service is enabled but not connected yet. Open Chrome to check again."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(
            onClick = onTestChrome,
            enabled = snapshot.chromeInstalled,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = PillShape,
        ) { Text("Test Chrome gate") }
        InfoCard(
            "What to expect",
            "Chrome may appear briefly before the gate covers it. Your tab stays unchanged.",
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, EditorialTheme.colors.hairlineSoft),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            actionLabel?.let {
                OutlinedButton(
                    onClick = onAction,
                    shape = PillShape,
                    border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                ) { Text(it) }
            }
        }
    }
}
