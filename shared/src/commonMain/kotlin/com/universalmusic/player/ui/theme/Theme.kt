package com.universalmusic.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.universalmusic.player.data.settings.ThemeMode
import kainosplayer.shared.generated.resources.Res
import kainosplayer.shared.generated.resources.ibm_plex_mono_medium
import kainosplayer.shared.generated.resources.ibm_plex_mono_regular
import kainosplayer.shared.generated.resources.ibm_plex_sans_medium
import kainosplayer.shared.generated.resources.ibm_plex_sans_regular
import kainosplayer.shared.generated.resources.ibm_plex_sans_semibold
import kainosplayer.shared.generated.resources.outfit_bold
import kainosplayer.shared.generated.resources.outfit_regular
import kainosplayer.shared.generated.resources.outfit_semibold
import org.jetbrains.compose.resources.Font

/** Near-black graphite for dark chrome and light-mode ink. */
val Graphite = Color(0xFF14151C)

/** Elevated dark plate for panels and mini player. */
val Plate = Color(0xFF1C1E28)

/** Cool daylight surface — deliberately not warm cream. */
val Fog = Color(0xFFEEF0F5)

/** Soft fog plate for light elevated surfaces. */
val FogPlate = Color(0xFFE2E5EE)

/** Studio meter / hi-fi LED accent. */
val Signal = Color(0xFF00A896)

val SignalDeep = Color(0xFF007F74)

val Mute = Color(0xFF7A8090)

val SpotifyGreen = Color(0xFF1DB954)
val YoutubeRed = Color(0xFFFF0033)
val LocalSignal = Color(0xFF3D7EFF)

private val DarkColors = darkColorScheme(
    primary = Signal,
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF005048),
    onPrimaryContainer = Color(0xFF8FF5E6),
    secondary = Color(0xFFB5C4FF),
    onSecondary = Color(0xFF1A2748),
    secondaryContainer = Color(0xFF2A3658),
    onSecondaryContainer = Color(0xFFDCE3FF),
    tertiary = Color(0xFFFFB59A),
    onTertiary = Color(0xFF5A1C00),
    background = Graphite,
    onBackground = Color(0xFFE8EAF2),
    surface = Graphite,
    onSurface = Color(0xFFE8EAF2),
    surfaceVariant = Plate,
    onSurfaceVariant = Color(0xFFB4B8C7),
    outline = Color(0xFF5C6170),
    outlineVariant = Color(0xFF3A3E4C),
    error = Color(0xFFFFB4AB),
)

private val LightColors = lightColorScheme(
    primary = SignalDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB6F0E6),
    onPrimaryContainer = Color(0xFF00201C),
    secondary = Color(0xFF44517A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE3FF),
    onSecondaryContainer = Color(0xFF0F1A36),
    tertiary = Color(0xFF8B4518),
    onTertiary = Color.White,
    background = Fog,
    onBackground = Graphite,
    surface = Fog,
    onSurface = Graphite,
    surfaceVariant = FogPlate,
    onSurfaceVariant = Color(0xFF4A5060),
    outline = Color(0xFF747987),
    outlineVariant = Color(0xFFC4C7D4),
    error = Color(0xFFBA1A1A),
)

@Composable
fun kainosTypography(): Typography {
    val display = FontFamily(
        Font(Res.font.outfit_regular, weight = FontWeight.Normal),
        Font(Res.font.outfit_semibold, weight = FontWeight.SemiBold),
        Font(Res.font.outfit_bold, weight = FontWeight.Bold),
    )
    val body = FontFamily(
        Font(Res.font.ibm_plex_sans_regular, weight = FontWeight.Normal),
        Font(Res.font.ibm_plex_sans_medium, weight = FontWeight.Medium),
        Font(Res.font.ibm_plex_sans_semibold, weight = FontWeight.SemiBold),
    )
    val mono = FontFamily(
        Font(Res.font.ibm_plex_mono_regular, weight = FontWeight.Normal),
        Font(Res.font.ibm_plex_mono_medium, weight = FontWeight.Medium),
    )
    return remember(display, body, mono) {
        Typography(
            displayLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp, letterSpacing = (-0.5).sp),
            displayMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-0.25).sp),
            displaySmall = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
            headlineLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
            headlineMedium = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
            headlineSmall = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
            titleLarge = TextStyle(fontFamily = display, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
            titleMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
            titleSmall = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.1.sp),
            bodyLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
            bodySmall = TextStyle(fontFamily = body, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp),
            labelLarge = TextStyle(fontFamily = body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.2.sp),
            labelMedium = TextStyle(fontFamily = mono, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.3.sp),
            labelSmall = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
        )
    }
}

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
        typography = kainosTypography(),
        content = content,
    )
}

fun providerColor(name: String): Color = when (name) {
    "Spotify" -> SpotifyGreen
    "YouTube Music" -> YoutubeRed
    "Local library" -> LocalSignal
    else -> Signal
}

@Composable
fun appColorScheme(): ColorScheme = MaterialTheme.colorScheme
