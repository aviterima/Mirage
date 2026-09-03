package com.mirage.spike

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Mirage brand colour: the indigo of the launcher icon. */
val Indigo = Color(0xFF4F46E5)

private val MirageColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0F766E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF134E4A),
    tertiary = Color(0xFF7C3AED),
    surface = Color.White,
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF5F6368),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF111827),
    error = Color(0xFFDC2626),
    onError = Color.White,
    outline = Color(0xFFCBD5E1),
)

/** One consistent light look; the map itself is light, so the chrome matches it. */
@Composable
fun MirageTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = MirageColors, content = content)
}
