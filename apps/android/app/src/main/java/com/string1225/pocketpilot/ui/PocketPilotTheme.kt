package com.string1225.pocketpilot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.string1225.pocketpilot.model.ThemePreference

private val PocketPilotLightColors = lightColorScheme(
    primary = Color(0xFF315DA8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E5FF),
    onPrimaryContainer = Color(0xFF0A2F66),
    secondary = Color(0xFF526078),
    secondaryContainer = Color(0xFFDCE4F8),
    tertiary = Color(0xFF6B5778),
    background = Color(0xFFF8F9FD),
    surface = Color(0xFFF8F9FD),
    surfaceVariant = Color(0xFFE4E7EE),
    error = Color(0xFFBA1A1A),
)

private val PocketPilotDarkColors = darkColorScheme(
    primary = Color(0xFFADC7FF),
    onPrimary = Color(0xFF002F67),
    primaryContainer = Color(0xFF154684),
    onPrimaryContainer = Color(0xFFD9E5FF),
    secondary = Color(0xFFBAC6DE),
    secondaryContainer = Color(0xFF3B485F),
    tertiary = Color(0xFFD7BDE4),
    background = Color(0xFF111318),
    surface = Color(0xFF111318),
    surfaceVariant = Color(0xFF44474F),
    error = Color(0xFFFFB4AB),
)

@Composable
fun PocketPilotTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val useDarkColors = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (useDarkColors) PocketPilotDarkColors else PocketPilotLightColors,
        content = content,
    )
}
