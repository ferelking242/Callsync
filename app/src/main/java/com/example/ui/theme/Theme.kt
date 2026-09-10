package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary              = AccentBlue,
    onPrimary            = BackgroundDark,
    primaryContainer     = AccentBlueSubtle,
    onPrimaryContainer   = AccentBlue,

    secondary            = StatusGreen,
    onSecondary          = BackgroundDark,
    secondaryContainer   = StatusGreenSubtle,
    onSecondaryContainer = StatusGreen,

    tertiary             = StatusOrange,
    onTertiary           = BackgroundDark,
    tertiaryContainer    = StatusOrangeSubtle,
    onTertiaryContainer  = StatusOrange,

    background           = BackgroundDark,
    onBackground         = TextPrimary,

    surface              = SurfaceDark,
    onSurface            = TextPrimary,
    surfaceVariant       = SurfaceElevated,
    onSurfaceVariant     = TextSecondary,

    outline              = SurfaceBorder,
    outlineVariant       = TextMuted,

    error                = StatusRed,
    onError              = BackgroundDark,
    errorContainer       = StatusRedSubtle,
    onErrorContainer     = StatusRed,
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography  = Typography,
        content     = content,
    )
}
