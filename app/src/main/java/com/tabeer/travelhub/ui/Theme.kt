package com.tabeer.travelhub.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.tabeer.travelhub.data.ThemeMode

private val Light = lightColorScheme(
    primary = Color(0xFF0B5CAD),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E6FA),
    onPrimaryContainer = Color(0xFF062B52),
    secondary = Color(0xFF00796B),
    background = Color(0xFFF5F8FC),
    surface = Color.White,
    surfaceVariant = Color(0xFFE6ECF4),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8DBBF0),
    onPrimary = Color(0xFF05233F),
    primaryContainer = Color(0xFF1B4A7F),
    onPrimaryContainer = Color(0xFFD6E6FA),
    secondary = Color(0xFF6FD3C6),
    background = Color(0xFF0F1620),
    surface = Color(0xFF17212D),
    surfaceVariant = Color(0xFF243142),
)

@Composable
fun TravelHubTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
