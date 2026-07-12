package com.michalkrsik.internetrestrictor.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.michalkrsik.internetrestrictor.domain.BudgetPeriod
import com.michalkrsik.internetrestrictor.domain.BudgetSnapshot
import com.michalkrsik.internetrestrictor.domain.MINUTE_MS
import com.michalkrsik.internetrestrictor.domain.ProtectionHealth
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.ui.components.BudgetBar
import com.michalkrsik.internetrestrictor.ui.components.HealthCard
import com.michalkrsik.internetrestrictor.ui.components.formatCompactTime
import com.michalkrsik.internetrestrictor.ui.components.formatLongTime
import com.michalkrsik.internetrestrictor.ui.components.friendlyLabel
import com.michalkrsik.internetrestrictor.ui.components.healthCopy

@Composable
fun DashboardScreen(
    snapshot: StateSnapshot,
    onOpenSettings: () -> Unit,
    onTestChrome: () -> Unit,
    onHealthAction: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp),
    ) {
        Text(
            "INTERNET RESTRICTOR",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.5.sp,
        )
        Spacer(Modifier.height(10.dp))
        Text("Today's boundary.", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(20.dp))

        StatusLine(snapshot)

        val copy = healthCopy(snapshot.enforcementHealth, snapshot.usageAccessGranted)
        if (!copy.positive || copy.action != null) {
            Spacer(Modifier.height(18.dp))
            HealthCard(
                health = snapshot.enforcementHealth,
                usageAccessGranted = snapshot.usageAccessGranted,
                onAction = onHealthAction,
            )
        }

        Spacer(Modifier.height(18.dp))
        HeroBudgetCard(snapshot)

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Text("Open settings")
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(
            onClick = onTestChrome,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp),
            enabled = snapshot.chromeInstalled,
        ) {
            Text("Test Chrome gate", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StatusLine(snapshot: StateSnapshot) {
    val hairline = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val (statusText, statusColor) = when {
        snapshot.enforcementHealth == ProtectionHealth.ENFORCEMENT_OFF ->
            "Enforcement off" to MaterialTheme.colorScheme.onSurfaceVariant
        snapshot.enforcementHealth in setOf(
            ProtectionHealth.READY,
            ProtectionHealth.READY_LIMITED_RECOVERY,
        ) -> "Chrome protected" to MaterialTheme.colorScheme.primary
        else -> "Needs attention" to MaterialTheme.colorScheme.error
    }
    val detail = buildString {
        append(if (snapshot.settings.delayEnabled) "Wait on" else "No wait")
        if (snapshot.settings.emergencySkipsEnabled) {
            append(" · ${snapshot.skipSnapshot.remaining} skips left")
        }
    }
    Column {
        HorizontalDivider(color = hairline)
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                Text(statusText, style = MaterialTheme.typography.titleMedium)
            }
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        snapshot.activeUnlock?.let {
            Text(
                "Session: ${formatLongTime(it.expiresAtWallMs - snapshot.wallNowMs)} left",
                modifier = Modifier.padding(bottom = 13.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = hairline)
    }
}

@Composable
private fun HeroBudgetCard(snapshot: StateSnapshot) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.13f)),
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 24.dp)) {
            val enabled = snapshot.budgetSnapshots.filter { it.enabled }
            if (enabled.isEmpty()) {
                Text(
                    "No budget currently blocks Chrome. Usage is still counted for every period.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            val hero = enabled.first()
            val remaining = hero.remainingMs ?: 0
            Text(
                heroEyebrow(hero.period),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (hero.exhausted) "0m" else formatCompactTime(remaining),
                style = MaterialTheme.typography.displayLarge,
                color = if (hero.exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "of ${formatCompactTime(hero.limitMinutes * MINUTE_MS)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            HeroTrack(hero)
            enabled.drop(1).forEach { budget ->
                Spacer(Modifier.height(18.dp))
                BudgetBar(budget, label = budget.period.friendlyLabel())
            }
        }
    }
}

@Composable
private fun HeroTrack(budget: BudgetSnapshot) {
    val color = if (budget.exhausted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .height(7.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(100)),
    ) {
        Box(
            Modifier
                .fillMaxWidth((budget.percentage / 100f).coerceIn(0f, 1f))
                .height(7.dp)
                .background(color, RoundedCornerShape(100)),
        )
    }
}

private fun heroEyebrow(period: BudgetPeriod): String = when (period) {
    BudgetPeriod.DAILY -> "TIME REMAINING TODAY"
    BudgetPeriod.WEEKLY -> "TIME REMAINING THIS WEEK"
    BudgetPeriod.MONTHLY -> "TIME REMAINING THIS MONTH"
}
