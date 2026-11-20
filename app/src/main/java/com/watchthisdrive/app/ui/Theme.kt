package com.watchthisdrive.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GolfColors = darkColorScheme(
    primary = Color(0xFFB8F36B), onPrimary = Color(0xFF101713),
    secondary = Color(0xFF8DBB70), background = Color(0xFF101713),
    surface = Color(0xFF18231C), onSurface = Color.White
)

@Composable
fun GolfTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = GolfColors, content = content)
}
