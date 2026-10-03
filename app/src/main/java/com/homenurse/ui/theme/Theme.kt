package com.homenurse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * HomeNurse theme: soft medical green on white, light-first, no Material You
 * dynamic colour — the identity is fixed by [HomeNurseColors] so the app
 * always matches the design system.
 *
 * Dark mode is supported as a courtesy (same hues, inverted surfaces); the
 * designed experience is the light theme.
 */

private val HomeNurseLightColors = lightColorScheme(
    primary = HomeNurseColors.Primary,
    onPrimary = HomeNurseColors.OnPrimary,
    primaryContainer = HomeNurseColors.PrimaryLight,
    onPrimaryContainer = HomeNurseColors.TextPrimary,
    secondary = HomeNurseColors.PrimaryDark,
    onSecondary = HomeNurseColors.OnPrimary,
    secondaryContainer = HomeNurseColors.SurfaceSoft,
    onSecondaryContainer = HomeNurseColors.TextPrimary,
    tertiary = HomeNurseColors.Success,
    onTertiary = HomeNurseColors.OnPrimary,
    tertiaryContainer = HomeNurseColors.InfoContainer,
    onTertiaryContainer = HomeNurseColors.OnInfoContainer,
    background = HomeNurseColors.Background,
    onBackground = HomeNurseColors.TextPrimary,
    surface = HomeNurseColors.Surface,
    onSurface = HomeNurseColors.TextPrimary,
    surfaceVariant = HomeNurseColors.SurfaceSoft,
    onSurfaceVariant = HomeNurseColors.TextSecondary,
    surfaceTint = HomeNurseColors.Primary,
    outline = HomeNurseColors.Border,
    outlineVariant = HomeNurseColors.Border,
    error = HomeNurseColors.Error,
    onError = HomeNurseColors.OnPrimary,
    errorContainer = HomeNurseColors.ErrorContainer,
    onErrorContainer = HomeNurseColors.OnErrorContainer,
)

private val HomeNurseDarkColors = darkColorScheme(
    primary = Color(0xFF7FD6A3),
    onPrimary = Color(0xFF07281A),
    primaryContainer = Color(0xFF1E5C3B),
    onPrimaryContainer = HomeNurseColors.PrimaryLight,
    secondary = Color(0xFFA8DCC0),
    onSecondary = Color(0xFF07281A),
    secondaryContainer = Color(0xFF1C2C22),
    onSecondaryContainer = Color(0xFFD9EEE1),
    tertiary = Color(0xFF8FD9A6),
    onTertiary = Color(0xFF07281A),
    tertiaryContainer = Color(0xFF1E5C3B),
    onTertiaryContainer = Color(0xFFD9EEE1),
    background = Color(0xFF0F1A14),
    onBackground = Color(0xFFE5F1EA),
    surface = Color(0xFF152219),
    onSurface = Color(0xFFE5F1EA),
    surfaceVariant = Color(0xFF1C2C22),
    onSurfaceVariant = Color(0xFFA9BCB1),
    surfaceTint = Color(0xFF7FD6A3),
    outline = Color(0xFF2E4137),
    outlineVariant = Color(0xFF2E4137),
    error = Color(0xFFE69B9B),
    onError = Color(0xFF3A1414),
    errorContainer = Color(0xFF5C2B2B),
    onErrorContainer = Color(0xFFF6DADA),
)

/**
 * Applies the HomeNurse design system: **light theme first, always** — the
 * design tokens are fixed light medical-green surfaces, and dynamic
 * (Material You) colour is deliberately disabled so every device shows the
 * same identity. System dark mode does not flip the app to dark surfaces.
 */
@Composable
fun HomeNurseTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        darkTheme -> HomeNurseDarkColors
        else -> HomeNurseLightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = HomeNurseTypography,
        shapes = HomeNurseShapes,
        content = content,
    )
}
