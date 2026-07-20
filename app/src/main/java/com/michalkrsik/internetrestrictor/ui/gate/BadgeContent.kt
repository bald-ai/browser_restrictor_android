package com.michalkrsik.internetrestrictor.ui.gate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.domain.StateSnapshot
import com.michalkrsik.internetrestrictor.domain.GateMode
import com.michalkrsik.internetrestrictor.ui.components.formatCompactTime
import com.michalkrsik.internetrestrictor.ui.components.formatReportTime
import com.michalkrsik.internetrestrictor.ui.components.lowerLabel

@Composable
fun BadgeContent(snapshot: StateSnapshot) {
    val offText = if (snapshot.canonicalMode == GateMode.ENFORCEMENT_DISABLED) {
        "Protection off | ${formatReportTime(snapshot.todayUsageReport.offUsageMs)} reported"
    } else if (snapshot.canonicalMode == GateMode.ACCESS_GRANTED_CURRENT_VISIT) {
        "Protection returns on your next Chrome visit"
    } else null
    val budget = snapshot.budgetSnapshots
        .filter { it.enabled }
        .minByOrNull { it.remainingMs ?: Long.MAX_VALUE }
    val budgetText = budget?.let {
        "${it.period.lowerLabel()} ${formatCompactTime(it.remainingMs ?: 0)} left"
    } ?: "no active budget"
    val unlockText = snapshot.activeUnlock?.let {
        " | unlock ${formatCompactTime(it.expiresAtWallMs - snapshot.wallNowMs)}"
    }.orEmpty()
    Box(
        Modifier
            .shadow(8.dp, RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            offText ?: "Chrome: $budgetText$unlockText",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
    }
}
