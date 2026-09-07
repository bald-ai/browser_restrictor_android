package com.michalkrsik.internetrestrictor.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.michalkrsik.internetrestrictor.R
import com.michalkrsik.internetrestrictor.domain.ThemePreference

// Pulse Day and Night share citrus accents, charcoal controls, and athletic typography.
val PulseYellow = Color(0xFFD6F66A)
val PulseCharcoal = Color(0xFF20261B)
val PulseDisplay = FontFamily(Font(R.font.pulse_heading, FontWeight.ExtraBold, FontStyle.Italic))
val PulseNumber = FontFamily(Font(R.font.pulse_number))
val PaperLight = Color(0xFFE6EDAC)
val Paper2Light = Color(0xFFD8E391)
val CardLight = Color(0xFFDCE69B)
val InkLight = Color(0xFF20261B)
val Ink2Light = Color(0xFF515D35)
val Ink3Light = Color(0xFF59663B)

val PaperDark = Color(0xFF22251F)
val Paper2Dark = Color(0xFF303927)
val CardDark = Color(0xFF2B3026)
val InkDark = Color(0xFFF4F5EC)
val Ink2Dark = Color(0xFFBAC5A5)
val Ink3Dark = Color(0xFFA1AA96)

val AccentLight = Color(0xFF3B5519)
val AccentInkLight = Color(0xFF334D1D)
val AccentDark = Color(0xFFD6F66A)
val AccentInkDark = Color(0xFFD6F66A)

val GoLight = Color(0xFF3B5519)
val WarnLight = Color(0xFF805014)
val LowLight = Color(0xFFA32F23)
val GoDark = Color(0xFFD6F66A)
val WarnDark = Color(0xFFEFC66C)
val LowDark = Color(0xFFFF9580)

val InstrumentSerif = FontFamily(
    Font(R.font.instrument_serif_regular, FontWeight.Normal),
    Font(R.font.instrument_serif_italic, FontWeight.Normal, FontStyle.Italic),
)

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
val Manrope = FontFamily(
    Font(R.font.pulse_body, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.pulse_body, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.pulse_body, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.pulse_body, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.pulse_body, FontWeight.ExtraBold, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

// Kept under the existing name so every app action adopts the same Pulse geometry.
val PillShape = RoundedCornerShape(9.dp)

@Immutable
class EditorialColors(
    val accent: Color,
    val accentInk: Color,
    val go: Color,
    val warn: Color,
    val low: Color,
    val ink3: Color,
    val hairline: Color,
    val hairlineSoft: Color,
)

private val EditorialLight = EditorialColors(
    accent = AccentLight,
    accentInk = AccentInkLight,
    go = GoLight,
    warn = WarnLight,
    low = LowLight,
    ink3 = Ink3Light,
    hairline = Color(0xFFADB979),
    hairlineSoft = Color(0xFF9EAC6B),
)

private val EditorialDark = EditorialColors(
    accent = AccentDark,
    accentInk = AccentInkDark,
    go = GoDark,
    warn = WarnDark,
    low = LowDark,
    ink3 = Ink3Dark,
    hairline = Color(0xFF404737),
    hairlineSoft = Color(0xFF404737),
)

val LocalEditorialColors = staticCompositionLocalOf { EditorialLight }

object EditorialTheme {
    val colors: EditorialColors
        @Composable
        get() = LocalEditorialColors.current
}

val SerifNote = TextStyle(
    fontFamily = Manrope,
    fontWeight = FontWeight.Normal,
    fontSize = 14.sp,
    lineHeight = 21.sp,
)

/** Preserve screen copy and accessibility labels while the theme supplies Pulse typography. */
@Composable
fun editorialTitle(text: String): AnnotatedString = AnnotatedString(text)

private val LightColors = lightColorScheme(
    primary = AccentLight,
    onPrimary = PaperLight,
    secondary = AccentLight,
    onSecondary = PaperLight,
    tertiary = AccentInkLight,
    onTertiary = PaperLight,
    error = LowLight,
    background = PaperLight,
    onBackground = InkLight,
    surface = CardLight,
    onSurface = InkLight,
    surfaceVariant = Paper2Light,
    onSurfaceVariant = Ink2Light,
    outline = Ink3Light,
    outlineVariant = Color(0xFFADB979),
    surfaceContainer = CardLight,
    surfaceContainerHigh = CardLight,
    surfaceContainerHighest = Paper2Light,
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = PulseYellow,
    onPrimary = PulseCharcoal,
    secondary = AccentDark,
    onSecondary = PaperDark,
    tertiary = AccentInkDark,
    onTertiary = PaperDark,
    error = LowDark,
    background = PaperDark,
    onBackground = InkDark,
    surface = CardDark,
    onSurface = InkDark,
    surfaceVariant = Paper2Dark,
    onSurfaceVariant = Ink2Dark,
    outline = Ink3Dark,
    outlineVariant = Color(0xFF404737),
    surfaceContainer = CardDark,
    surfaceContainerHigh = CardDark,
    surfaceContainerHighest = Paper2Dark,
    surfaceTint = Color.Transparent,
)

private val RestrictorTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = PulseDisplay,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 64.sp,
        lineHeight = 66.sp,
        letterSpacing = (-1.5).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = PulseDisplay,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-0.5).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = PulseDisplay,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 30.sp,
        lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = PulseDisplay,
        fontWeight = FontWeight.ExtraBold,
        fontStyle = FontStyle.Italic,
        fontSize = 26.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 20.sp),
    titleMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
    bodyLarge = TextStyle(fontFamily = Manrope, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Manrope, fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 12.sp),
    labelSmall = TextStyle(
        fontFamily = Manrope,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
    ),
)

@Composable
fun RestrictorTheme(
    preference: ThemePreference,
    content: @Composable () -> Unit,
) {
    val dark = preference == ThemePreference.DARK
    androidx.compose.runtime.CompositionLocalProvider(
        LocalEditorialColors provides if (dark) EditorialDark else EditorialLight,
    ) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = RestrictorTypography,
            content = content,
        )
    }
}
