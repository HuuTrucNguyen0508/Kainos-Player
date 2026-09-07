package com.universalmusic.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.universalmusic.player.data.settings.ThemeMode

val Amber = Color(0xFFE8A54B)
val AmberDeep = Color(0xFFC9842A)
val Ink = Color(0xFF101014)
val InkElevated = Color(0xFF1A1A21)
val Cream = Color(0xFFF7F1E8)
val SpotifyGreen = Color(0xFF1DB954)
val YoutubeRed = Color(0xFFFF0033)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = Color(0xFF2A1C00),
    primaryContainer = Color(0xFF3F2E10),
    onPrimaryContainer = Color(0xFFFFE3B0),
    secondary = Color(0xFFD5C4A1),
    onSecondary = Color(0xFF2B2416),
    secondaryContainer = Color(0xFF3A3326),
    onSecondaryContainer = Color(0xFFEDE3CF),
    tertiary = Color(0xFF8FCBB8),
    background = Ink,
    onBackground = Color(0xFFF4EFE6),
    surface = Ink,
    onSurface = Color(0xFFF4EFE6),
    surfaceVariant = InkElevated,
    onSurfaceVariant = Color(0xFFC9C2B6),
    outline = Color(0xFF5C564B),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = AmberDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE3B0),
    onPrimaryContainer = Color(0xFF2A1C00),
    secondary = Color(0xFF6B5D45),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E4CC),
    onSecondaryContainer = Color(0xFF241C0E),
    tertiary = Color(0xFF3D6B5E),
    background = Cream,
    onBackground = Color(0xFF1C1914),
    surface = Cream,
    onSurface = Color(0xFF1C1914),
    surfaceVariant = Color(0xFFE8DFD0),
    onSurfaceVariant = Color(0xFF51483B),
    outline = Color(0xFF837868),
)

@Composable
fun UniversalMusicTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

fun providerColor(name: String): Color = when (name) {
    "Spotify" -> SpotifyGreen
    "YouTube Music" -> YoutubeRed
    else -> Amber
}

@Composable
fun appColorScheme(): ColorScheme = MaterialTheme.colorScheme
