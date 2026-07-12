package com.michalkrsik.internetrestrictor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.michalkrsik.internetrestrictor.R
import com.michalkrsik.internetrestrictor.domain.ThemePreference

val LightBackground = Color(0xFFF6F4EE)
val LightPaper = Color(0xFFFFFFFF)
val LightSoft = Color(0xFFEFECE4)
val LightInk = Color(0xFF0E1A2E)
val LightSecondary = Color(0xFF5D6B82)
val LightMuted = Color(0xFFA4AFC1)

val DarkBackground = Color(0xFF0A1322)
val DarkPaper = Color(0xFF152236)
val DarkSoft = Color(0xFF1D2C44)
val DarkInk = Color(0xFFEEF2F9)
val DarkSecondary = Color(0xFF94A0B8)
val DarkMuted = Color(0xFF445268)

val Emerald = Color(0xFF54D8A7)
val EmeraldLight = Color(0xFF1EB980)
val Violet = Color(0xFFA78BFA)
val VioletLight = Color(0xFF8B6DFF)
val Mint = Color(0xFF5EEAD4)
val Danger = Color(0xFFFB7185)
val DangerLight = Color(0xFFE1546E)

val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

val Manrope = FontFamily(
    Font(R.font.manrope_variable, FontWeight.Normal),
    Font(R.font.manrope_variable, FontWeight.Medium),
    Font(R.font.manrope_variable, FontWeight.SemiBold),
    Font(R.font.manrope_variable, FontWeight.Bold),
    Font(R.font.manrope_variable, FontWeight.ExtraBold),
)

private val LightColors = lightColorScheme(
    primary = EmeraldLight,
    onPrimary = Color.White,
    secondary = VioletLight,
    onSecondary = Color.White,
    error = DangerLight,
    background = LightBackground,
    onBackground = LightInk,
    surface = LightPaper,
    onSurface = LightInk,
    surfaceVariant = LightSoft,
    onSurfaceVariant = LightSecondary,
    outline = LightMuted,
)

private val DarkColors = darkColorScheme(
    primary = Emerald,
    onPrimary = DarkBackground,
    secondary = Violet,
    onSecondary = DarkBackground,
    error = Danger,
    background = DarkBackground,
    onBackground = DarkInk,
    surface = DarkPaper,
    onSurface = DarkInk,
    surfaceVariant = DarkSoft,
    onSurfaceVariant = DarkSecondary,
    outline = DarkMuted,
)

private val RestrictorTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = InstrumentSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 52.sp,
        lineHeight = 54.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = InstrumentSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 40.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.3).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = InstrumentSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 32.sp,
        lineHeight = 35.sp,
    ),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 10.sp),
)

@Composable
fun RestrictorTheme(
    preference: ThemePreference,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (preference == ThemePreference.DARK) DarkColors else LightColors,
        typography = RestrictorTypography,
        content = content,
    )
}

