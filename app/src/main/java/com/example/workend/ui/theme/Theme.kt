package com.example.workend.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF4A5AE8),
    onPrimary = Color.White,
    secondary = Color(0xFF00897B),
    tertiary = Color(0xFFFF7043),
    background = Color(0xFFF7F8FC),
    surface = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF1B2260),
    secondary = Color(0xFF63D8CB),
    tertiary = Color(0xFFFFB59D),
    background = Color(0xFF11131A),
    surface = Color(0xFF1A1C24),
)

@Composable
fun WorkEndTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
