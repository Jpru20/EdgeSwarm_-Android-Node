package com.xgpicks.edgeswarm.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val SwarmLightScheme = lightColorScheme(
    primary = SwarmPrimary,
    onPrimary = Color.White,
    secondary = SwarmEmerald,
    onSecondary = Color.White,
    tertiary = Color(0xFF009BA3),
    background = SwarmBackground,
    onBackground = SwarmText,
    surface = SwarmSurface,
    onSurface = SwarmText,
    outline = SwarmBorder
)

@Composable
fun EdgeSwarmTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Intentional: preserve the Swarm brand, not Android wallpaper colors.
    MaterialTheme(
        colorScheme = SwarmLightScheme,
        typography = Typography,
        content = content
    )
}