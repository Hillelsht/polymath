package com.hillelsht.smart.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * Smart's palette.
 *
 * Deliberately *not* Material You dynamic colour. A knowledge app leans on category identity —
 * Geography teal, History amber, Science blue — and dynamic theming would repaint those
 * accents from the user's wallpaper, destroying the one visual cue that says which subject a
 * card belongs to.
 */
object SmartPalette {
    val Ink = Color(0xFF070B14)
    val Surface = Color(0xFF0E1524)
    val SurfaceElevated = Color(0xFF162034)
    val Outline = Color(0xFF243350)

    val Iris = Color(0xFF6C5CE7)
    val IrisBright = Color(0xFF8B7BFF)
    val Mint = Color(0xFF00C2A8)

    val TextPrimary = Color(0xFFF2F5FF)
    val TextSecondary = Color(0xFF9AA9CC)
    val TextTertiary = Color(0xFF64749A)

    val Success = Color(0xFF3DDC97)
    val Warning = Color(0xFFF2C94C)
    val Danger = Color(0xFFFF6B6B)

    // Light theme
    val LightBackground = Color(0xFFF6F7FC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceElevated = Color(0xFFEFF2FA)
    val LightOutline = Color(0xFFD8DEEE)
    val LightTextPrimary = Color(0xFF0D1426)
    val LightTextSecondary = Color(0xFF515F82)
}

private val DarkColors = darkColorScheme(
    primary = SmartPalette.IrisBright,
    onPrimary = Color.White,
    primaryContainer = SmartPalette.Iris,
    onPrimaryContainer = Color.White,
    secondary = SmartPalette.Mint,
    onSecondary = Color(0xFF00110E),
    background = SmartPalette.Ink,
    onBackground = SmartPalette.TextPrimary,
    surface = SmartPalette.Surface,
    onSurface = SmartPalette.TextPrimary,
    surfaceVariant = SmartPalette.SurfaceElevated,
    onSurfaceVariant = SmartPalette.TextSecondary,
    outline = SmartPalette.Outline,
    error = SmartPalette.Danger,
)

private val LightColors = lightColorScheme(
    primary = SmartPalette.Iris,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E0FF),
    onPrimaryContainer = Color(0xFF241B62),
    secondary = Color(0xFF00897B),
    onSecondary = Color.White,
    background = SmartPalette.LightBackground,
    onBackground = SmartPalette.LightTextPrimary,
    surface = SmartPalette.LightSurface,
    onSurface = SmartPalette.LightTextPrimary,
    surfaceVariant = SmartPalette.LightSurfaceElevated,
    onSurfaceVariant = SmartPalette.LightTextSecondary,
    outline = SmartPalette.LightOutline,
    error = Color(0xFFD32F2F),
)

/**
 * A tighter type scale than the Material default: display sizes are heavier and letter-spaced
 * negatively so headline numbers (streaks, scores, mastery) read as designed rather than as
 * default system text.
 */
private val SmartTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 44.sp,
        lineHeight = 48.sp,
        letterSpacing = (-1).sp,
    ),
    displayMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
    ),
    bodyLarge = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    labelLarge = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.sp,
    ),
)

/** True when the current theme is the dark one, for components that need to adapt shadows. */
val LocalIsDarkTheme = staticCompositionLocalOf { true }

@Composable
fun SmartTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colors,
            typography = SmartTypography,
            content = content,
        )
    }
}
