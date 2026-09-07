package com.universalmusic.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.universalmusic.player.data.settings.AppColorScheme
import com.universalmusic.player.data.settings.ThemeMode

val SpotifyGreen = Color(0xFF1DB954)
val YoutubeRed = Color(0xFFFF0033)
val LocalSignal = Color(0xFF75CF9D)

@Composable
fun UniversalMusicTheme(
    themeMode: ThemeMode,
    colorSchemeId: AppColorScheme = AppColorScheme.OLIVE,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = colorSchemeFor(colorSchemeId, dark),
        typography = Typography(),
        content = content,
    )
}

fun providerColor(name: String): Color = when (name) {
    "Spotify" -> SpotifyGreen
    "YouTube Music" -> YoutubeRed
    "Local library" -> LocalSignal
    else -> Color(0xFFB8CE9D)
}

@Composable
fun appColorScheme(): ColorScheme = MaterialTheme.colorScheme
