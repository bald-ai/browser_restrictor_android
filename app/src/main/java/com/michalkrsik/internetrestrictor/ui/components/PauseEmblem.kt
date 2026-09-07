package com.michalkrsik.internetrestrictor.ui.components

import com.michalkrsik.internetrestrictor.ui.theme.PulseIcon
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.ui.theme.PaperLight
import com.michalkrsik.internetrestrictor.ui.theme.PulseCharcoal
import com.michalkrsik.internetrestrictor.ui.theme.PulseYellow

@Composable
fun PauseEmblem(modifier: Modifier = Modifier) {
    val day = MaterialTheme.colorScheme.background == PaperLight
    val background = if (day) PulseCharcoal else PulseYellow
    val ink = if (day) PulseYellow else PulseCharcoal
    Box(modifier.size(55.dp).rotate(-12f).background(background, CircleShape), contentAlignment = Alignment.Center) {
        PulseIcon("pause", 30, ink)
    }
}
