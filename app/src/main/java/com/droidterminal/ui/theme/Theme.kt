package com.droidterminal.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = AccentCyan,
    secondary = AccentGreen,
    background = NeoBackground,
    surface = NeoSurface,
    onPrimary = NeoBackground,
    onSecondary = NeoBackground,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun DroidTerminalTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
