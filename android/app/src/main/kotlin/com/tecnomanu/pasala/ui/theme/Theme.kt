package com.tecnomanu.pasala.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.tecnomanu.pasala.R

val Orange = Color(0xFFFF6B3D)
val Violet = Color(0xFF7C5CFF)
val Mint = Color(0xFF3DFFB0)
val Rose = Color(0xFFF2557A)

val BrandGradient = Brush.linearGradient(listOf(Orange, Rose, Violet))
fun brandHorizontal() = Brush.horizontalGradient(listOf(Orange, Violet))

@Immutable
data class PasalaColors(
    val dark: Boolean,
    val ink: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val text: Color,
    val muted: Color,
    val faint: Color,
    val outline: Color,
    val success: Color,
    val danger: Color,
)

private val DarkPalette = PasalaColors(
    dark = true,
    ink = Color(0xFF0E0F1A),
    surface = Color(0xFF181A2A),
    surfaceHigh = Color(0xFF222538),
    text = Color(0xFFF4F2FF),
    muted = Color(0xFF9C9EBA),
    faint = Color(0xFF5E6180),
    outline = Color(0xFF2A2D45),
    success = Mint,
    danger = Color(0xFFFF5C7A),
)

private val LightPalette = PasalaColors(
    dark = false,
    ink = Color(0xFFF6F4EF),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEFEBE3),
    text = Color(0xFF17182B),
    muted = Color(0xFF6A6C80),
    faint = Color(0xFFA9AAB8),
    outline = Color(0xFFE6E1D6),
    success = Color(0xFF0FA968),
    danger = Color(0xFFE0334F),
)

val LocalPasala = staticCompositionLocalOf { DarkPalette }

object P {
    val c: PasalaColors @Composable get() = LocalPasala.current
}

val Jakarta = FontFamily(
    Font(R.font.jakarta_regular, FontWeight.Normal),
    Font(R.font.jakarta_medium, FontWeight.Medium),
    Font(R.font.jakarta_semi_bold, FontWeight.SemiBold),
    Font(R.font.jakarta_bold, FontWeight.Bold),
    Font(R.font.jakarta_extra_bold, FontWeight.ExtraBold),
)

private val PasalaType = Typography(
    displaySmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, letterSpacing = (-0.03).em, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, letterSpacing = (-0.025).em, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = (-0.02).em, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.01).em),
    titleMedium = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    bodyLarge = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.Bold, fontSize = 16.sp, letterSpacing = 0.01.em),
    labelMedium = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 0.06.em),
    labelSmall = TextStyle(fontFamily = Jakarta, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.04.em),
)

@Composable
fun PasalaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) darkColorScheme(
        primary = Orange, onPrimary = Color.White, secondary = Violet, onSecondary = Color.White,
        tertiary = Mint, background = p.ink, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted, outline = p.outline, outlineVariant = p.outline,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHigh,
        surfaceContainerLow = p.surface, error = p.danger,
    ) else lightColorScheme(
        primary = Orange, onPrimary = Color.White, secondary = Violet, onSecondary = Color.White,
        tertiary = p.success, background = p.ink, onBackground = p.text, surface = p.surface, onSurface = p.text,
        surfaceVariant = p.surfaceHigh, onSurfaceVariant = p.muted, outline = p.outline, outlineVariant = p.outline,
        surfaceContainer = p.surface, surfaceContainerHigh = p.surfaceHigh, surfaceContainerHighest = p.surfaceHigh,
        surfaceContainerLow = p.surface, error = p.danger,
    )
    CompositionLocalProvider(LocalPasala provides p) {
        MaterialTheme(colorScheme = scheme, typography = PasalaType, content = content)
    }
}
