package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = ElectricPurple,
    onPrimary = SpaceBlack,
    secondary = NeonTurquoise,
    onSecondary = SpaceBlack,
    tertiary = CyberPink,
    onTertiary = SpaceBlack,
    background = SpaceBlack,
    onBackground = OnBackgroundLight,
    surface = DeepVioletSurface,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    primaryContainer = ActiveCardBg,
    onPrimaryContainer = ElectricPurple,
    secondaryContainer = DeepVioletSurface,
    onSecondaryContainer = NeonTurquoise,
    error = Color(0xFFFF5252),
    onError = SpaceBlack
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6200EE),
    onPrimary = Color.White,
    secondary = Color(0xFF03DAC6),
    onSecondary = Color.Black,
    tertiary = Color(0xFFE91E63),
    background = Color(0xFFF5F5FA),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = Color(0xFFECE6F0),
    onSurfaceVariant = Color(0xFF49454F)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true, // Force premium dark theme by default
    dynamicColor: Boolean = false, // Set to false to ensure our gorgeous custom theme is used
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
