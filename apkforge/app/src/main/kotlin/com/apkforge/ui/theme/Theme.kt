package com.apkforge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

private val Accent = Color(0xFF4ADCA6)
private val AccentDim = Color(0xFF2E9E78)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF04140D),
    secondary = Color(0xFF7CC5F2),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF151A21),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1E2630),
    onSurfaceVariant = Color(0xFFB4C0CE),
    error = Color(0xFFFF6B6B),
)

private val LightColors = lightColorScheme(
    primary = AccentDim,
    onPrimary = Color.White,
    secondary = Color(0xFF2A6FA0),
    background = Color(0xFFF6F8FA),
    onBackground = Color(0xFF101418),
    surface = Color.White,
    onSurface = Color(0xFF101418),
    surfaceVariant = Color(0xFFE6EBF1),
    onSurfaceVariant = Color(0xFF45505C),
    error = Color(0xFFC62828),
)

/** Monospace type used by the file tree, code editor and build logs. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace)

@Composable
fun ApkForgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content,
    )
}
