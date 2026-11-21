package io.github.gabrielaian.stickerita

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0A2E0D),
    secondary = Color(0xFF558B2F),
    background = Color(0xFFF8FBF5),
    surface = Color(0xFFF8FBF5),
    surfaceVariant = Color(0xFFE1EBDD),
    onSurfaceVariant = Color(0xFF424940),
    surfaceContainerHighest = Color(0xFFE1EBDD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF81C784),
    onPrimary = Color(0xFF0A3D0F),
    primaryContainer = Color(0xFF1B5E20),
    onPrimaryContainer = Color(0xFFC8E6C9),
    secondary = Color(0xFFAED581),
    background = Color(0xFF10150F),
    surface = Color(0xFF10150F),
    surfaceVariant = Color(0xFF3A443A),
    onSurfaceVariant = Color(0xFFC1CBBE),
    surfaceContainerHighest = Color(0xFF2C352B),
)

@Composable
fun StickeritaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
