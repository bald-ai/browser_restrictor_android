package com.michalkrsik.internetrestrictor.ui.onboarding

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.michalkrsik.internetrestrictor.ui.components.PermissionStateRows
import com.michalkrsik.internetrestrictor.ui.theme.EditorialTheme
import com.michalkrsik.internetrestrictor.ui.theme.PillShape
import com.michalkrsik.internetrestrictor.ui.theme.pulseButtonColors
import com.michalkrsik.internetrestrictor.ui.theme.PulseActionBorder
import com.michalkrsik.internetrestrictor.ui.theme.editorialTitle

@Composable
fun OnboardingScreen(
    snapshot: StateSnapshot,
    onOpenAccessibility: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onTestBrowser: () -> Unit,
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
            else -> ReadyStep(
                snapshot = snapshot,
                onOpenAccessibility = onOpenAccessibility,
                onOpenUsageAccess = onOpenUsageAccess,
                onTestBrowser = onTestBrowser,
            )
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
            colors = pulseButtonColors(),
            border = PulseActionBorder,
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
        Text(editorialTitle("Pause before browsing."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Internet Restrictor adds a deliberate pause before supported browsers and X, then measures their active use against today's shared time pool.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard(
            if (com.michalkrsik.internetrestrictor.BuildConfig.IS_V1) "Your setup, connected" else "Local by design",
            if (com.michalkrsik.internetrestrictor.BuildConfig.IS_V1) "This experimental app has its own backend and installation identity. Settings and short activity summaries sync there. Voice uses OpenAI; activity evidence is sent for verification. Blocking, timers and balances keep working on your phone. No signup is needed."
            else "Settings and usage stay on this device. Challenge videos are temporarily sent through the owner's verifier and OpenRouter, then deleted. There is no account, advertising ID, or analytics service.",
        )
        InfoCard(
            "A gentle boundary",
            "You remain in control. Disabling Accessibility, using another browser, or turning enforcement off bypasses the app by design.",
        )
    }
}

@Composable
private fun DefaultsStep() {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Your starting rules."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "These rules apply across Chrome, Firefox, Edge, Opera, and X.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (com.michalkrsik.internetrestrictor.BuildConfig.IS_V1) {
            InfoCard("Earn your time", "Complete video or GPS tasks to earn credits and browsing minutes. Partial progress from different tasks combines. Today's time and unfinished progress expire at your daily reset.")
            InfoCard("Make it yours", "Talk to Friction or use ordinary controls to edit tasks, rewards, reset times and waits. Review proposed changes on cards and apply by button or voice. Undo restores the previous values.")
            InfoCard("Stay in control", "Voice and verification need the internet. GPS recordings save on your phone for later validation. Existing blocking rules continue if your connection drops.")
        } else {
        InfoCard("Daily budget", "Choose a daily allowance from 0 to 30 minutes in settings. It arrives at your daily reset; tasks earn extra time.")
        InfoCard("Earn more", "Every 5 points earns +3 minutes. 1 push-up, 2 machine reps, or 3s plank earns 1 point. Mix exercises; partial points carry until 04:00.")
        InfoCard("Monthly history", "Every verified push-up is counted in permanent 9th-to-9th monthly history. Plank and machine work stay off that ledger.")
        InfoCard("Sessions", "Choose 3, 5, or 10 minutes by default. You can customize all three presets in Settings. Every normal opening has one fixed 12-second focused wait.")
        }
        InfoCard(
            "Emergency unlock",
            if (com.michalkrsik.internetrestrictor.BuildConfig.IS_V1) "Your first credit of the day earns one emergency unlock. It opens access immediately without spending your earned browsing time. It starts at 15 minutes; you can change its duration in Customize."
            else "Each day starts with none. Your first completed exercise set earns one 15-minute emergency unlock for that day. It starts immediately and won’t use earned time.",
        )
    }
}

@Composable
private fun AccessibilityStep(
    enabled: Boolean,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Allow the browser gate."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Accessibility access is required for the real-time gate. Android asks you to enable it manually.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DisclosureCard()
        Button(
            colors = pulseButtonColors(),
            border = PulseActionBorder,
            onClick = onOpenAccessibility,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = PillShape,
        ) { Text(if (enabled) "Accessibility enabled ✓" else "Open Accessibility settings") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InfoCard(
                "If Android blocks the switch",
                "For a sideloaded build, open App info, tap the three-dot menu, choose Allow restricted settings, then return to Accessibility.",
                actionLabel = "Open app info",
                onAction = onOpenAppInfo,
            )
        }
        if (!enabled) {
            Text(
                "Setup cannot claim browsers are protected until Android reports this access as enabled.",
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
            Text("What the service does", style = MaterialTheme.typography.titleMedium)
            listOf(
                "Observes which app is currently in front.",
                "Only needs to know whether a supported browser, X, or YouTube is active.",
                "Draws the pause screen over supported browsers and X.",
                "Measures active, focused browser and X time in one shared pool.",
                "Does not inspect URLs, webpages, text, typing, or screenshots.",
                if (com.michalkrsik.internetrestrictor.BuildConfig.IS_V1) "Keeps blocking and balances on this phone; syncs configuration and short activity summaries to your separate backend." else "Keeps usage and settings on this device.",
            ).forEach { Text("•  $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun UsageStep(granted: Boolean, onOpenUsageAccess: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Improve recovery."), style = MaterialTheme.typography.headlineLarge)
        Text(
            if (com.michalkrsik.internetrestrictor.BuildConfig.IS_V1) "Usage Access helps check which protected app was active if Android briefly interrupts Friction. Accessibility access runs the live blocking screen."
            else "Usage Access can help confirm recent browser boundaries after a brief process interruption. It is not used to trigger the gate.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        InfoCard(
            "Real-time protection stays honest",
            "The Accessibility service remains the source for focus and overlays. Ambiguous gaps are left uncounted rather than guessed.",
        )
        Button(
            colors = pulseButtonColors(),
            border = PulseActionBorder,
            onClick = onOpenUsageAccess,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = PillShape,
        ) { Text(if (granted) "Usage Access granted ✓" else "Open Usage Access settings") }
        Text(
            if (granted) {
                if (com.michalkrsik.internetrestrictor.BuildConfig.IS_V1) "Permission granted. Recovery is still limited: time while Friction was interrupted may not be fully accounted for. Your saved rules and balances are kept."
                else "Permission granted. This personal MVP still reports limited recovery until the store-ready reconciliation suite is validated."
            } else {
                "You may continue without it. The dashboard will clearly say Protected with limited recovery."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyStep(
    snapshot: StateSnapshot,
    onOpenAccessibility: () -> Unit,
    onOpenUsageAccess: () -> Unit,
    onTestBrowser: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(editorialTitle("Confirm system access."), style = MaterialTheme.typography.headlineLarge)
        Text(
            "Required protection and recommended recovery access remain visible separately, with a repair action beside anything that needs attention.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PermissionStateRows(
            snapshot = snapshot,
            onOpenAccessibility = onOpenAccessibility,
            onOpenUsageAccess = onOpenUsageAccess,
        )
        Button(
            colors = pulseButtonColors(),
            border = PulseActionBorder,
            onClick = onTestBrowser,
            enabled = snapshot.supportedBrowserInstalled,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = PillShape,
        ) { Text("Test browser gate") }
        InfoCard(
            "What to expect",
            "The browser may draw a brief frame before Android sends the foreground event. The pause screen should then block browser touches without changing the current tab, private-browsing state, or destination.",
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
