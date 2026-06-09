package com.splitexpense.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GraphiteScheme = darkColorScheme(
    primary              = NeonGreen,
    onPrimary            = Bg,
    primaryContainer     = Color(0xFF0D2818),
    onPrimaryContainer   = NeonGreen,
    secondary            = AmberAccent,
    onSecondary          = Bg,
    secondaryContainer   = Color(0xFF2D1F00),
    onSecondaryContainer = AmberAccent,
    tertiary             = Text2,
    onTertiary           = Bg,
    tertiaryContainer    = Surface3,
    onTertiaryContainer  = Text1,
    error                = NeonRed,
    onError              = Bg,
    errorContainer       = Color(0xFF2D0C0C),
    onErrorContainer     = NeonRed,
    background           = Bg,
    onBackground         = Text1,
    surface              = Surface1,
    onSurface            = Text1,
    surfaceVariant       = Surface2,
    onSurfaceVariant     = Text2,
    surfaceContainer     = Surface2,
    surfaceContainerLow  = Surface1,
    surfaceContainerHigh = Surface3,
    outline              = Border,
    outlineVariant       = BorderHi,
    scrim                = Color(0xCC000000),
    inverseSurface       = Text1,
    inverseOnSurface     = Bg,
    inversePrimary       = Color(0xFF006B30),
)

@Composable
fun FairShareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GraphiteScheme,
        typography  = AppTypography,
        content     = content
    )
}
