package com.michalkrsik.internetrestrictor.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.domain.ThemePreference

/** Yellow actions keep the same contrast and priority in both appearances. */
@Composable
fun pulseButtonColors() = ButtonDefaults.buttonColors(
    containerColor = PulseYellow,
    contentColor = PulseCharcoal,
    disabledContainerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f),
)

val PulseActionBorder = BorderStroke(1.dp, PulseCharcoal)

/** An intentionally dark control group, including when the surrounding screen is Day. */
@Composable
fun PulsePanel(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    RestrictorTheme(ThemePreference.DARK) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = CardDark,
            contentColor = InkDark,
            border = BorderStroke(1.dp, EditorialTheme.colors.hairline),
        ) {
            Column(Modifier.padding(16.dp), content = content)
        }
    }
}
