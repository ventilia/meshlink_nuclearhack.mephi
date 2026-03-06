package com.example.meshlink.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MeshLinkColorScheme = darkColorScheme(
    primary          = PixelAccent,
    onPrimary        = PixelBlack,
    secondary        = PixelAccentDark,
    onSecondary      = PixelBlack,
    tertiary         = PixelPurpleLight,
    background       = PixelBlack,
    onBackground     = PixelText,
    surface          = PixelSurface,
    onSurface        = PixelText,
    surfaceVariant   = PixelMidGray,
    onSurfaceVariant = PixelTextDim,
    outline          = PixelBorder,
    error            = PixelWarn,
    onError          = PixelText
)

@Composable
fun MeshLinkTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MeshLinkColorScheme,
        typography = MeshLinkTypography,
        content = content
    )
}