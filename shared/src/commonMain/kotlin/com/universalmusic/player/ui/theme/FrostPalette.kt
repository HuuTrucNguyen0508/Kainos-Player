package com.universalmusic.player.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * The glass of the desktop shell's Frost style (`styles/frost/Tokens.qml` in the Caelestia shell):
 * Mist glass in light mode, Smoke glass in dark mode. Fills run top to bottom over the wallpaper.
 */
data class FrostGlass(
    val fogTop: Color, val fogBottom: Color,
    val denseTop: Color, val denseBottom: Color,
    val tallTop: Color, val tallBottom: Color,
    val rim: Color, val edge: Color,
    val specularAlpha: Float,
    /** Laid over the blurred wallpaper so glass keeps its contrast where the photo turns to rock. */
    val veil: Color,
    /** The mist drawn when there is no wallpaper to show (Android, or the file is missing). */
    val mistTop: Color, val mistBottom: Color,
    val coverPlateTop: Color, val coverPlateBottom: Color,
)

fun frostGlass(dark: Boolean): FrostGlass = if (dark) FrostGlass(
    fogTop = Color(26, 27, 30, (.58f * 255).toInt()), fogBottom = Color(14, 15, 17, (.74f * 255).toInt()),
    denseTop = Color(16, 17, 19, (.86f * 255).toInt()), denseBottom = Color(10, 11, 12, (.90f * 255).toInt()),
    tallTop = Color(26, 27, 30, (.64f * 255).toInt()), tallBottom = Color(10, 11, 12, (.90f * 255).toInt()),
    rim = Color.White.copy(alpha = .34f), edge = Color.White.copy(alpha = .10f),
    specularAlpha = .10f,
    veil = Color.Black.copy(alpha = .40f),
    mistTop = Color(0xFF5E6064), mistBottom = Color(0xFF1E1F22),
    coverPlateTop = Color(0xFFA9ABAD), coverPlateBottom = Color(0xFF4B4D50),
) else FrostGlass(
    fogTop = Color.White.copy(alpha = .40f), fogBottom = Color.White.copy(alpha = .26f),
    denseTop = Color.White.copy(alpha = .76f), denseBottom = Color.White.copy(alpha = .68f),
    tallTop = Color.White.copy(alpha = .44f), tallBottom = Color.White.copy(alpha = .78f),
    rim = Color.White.copy(alpha = .92f), edge = Color.White.copy(alpha = .34f),
    specularAlpha = .42f,
    veil = Color.White.copy(alpha = .18f),
    mistTop = Color(0xFFDADBDD), mistBottom = Color(0xFF8E9094),
    coverPlateTop = Color(0xFFA9ABAD), coverPlateBottom = Color(0xFF4B4D50),
)

/**
 * Frost has no colour accent: emphasis inverts ink and ground. Material slots hold opaque greys close
 * to the glass they sit on, so menus and dialogs stay readable over moving content.
 */
fun frostPalette(dark: Boolean): KainosPalette {
    val ink = if (dark) Color(0xFFF4F4F3) else Color(0xFF1C1E21)
    val inverse = if (dark) Color(0xFF141518) else Color.White
    val lowest = if (dark) Color(0xFF1B1C1F) else Color(0xFFF1F2F3)
    val low = if (dark) Color(0xFF222326) else Color(0xFFE9EAEB)
    val mid = if (dark) Color(0xFF2A2B2F) else Color(0xFFF4F5F6)
    val high = if (dark) Color(0xFF303135) else Color(0xFFE2E3E5)
    val highest = if (dark) Color(0xFF37383C) else Color(0xFFD9DADC)
    val ground = if (dark) Color(0xFF26272A) else Color(0xFFD3D4D6)
    val muted = contrastingColor(if (dark) Color(0xFFBDBDBC) else Color(0xFF40444A), highest, ink, 4.5)
    val neutral = if (dark) Color(0xFF8E9196) else Color(0xFF6B6F75)
    // Hues only echo a label, so they keep a trace of colour; red keeps more because it means failure.
    fun hue(color: Color, keep: Float = .28f) = lerp(neutral, color, keep)
    val accents = AccentRamp(
        red = hue(Color(0xFFE5484D), .62f), orange = hue(Color(0xFFFF8A3D)), yellow = hue(Color(0xFFF5B800)),
        green = hue(Color(0xFF30A46C)), teal = hue(Color(0xFF12A594)), blue = hue(Color(0xFF2D8CFF)),
        purple = hue(Color(0xFF6E56CF)), pink = hue(Color(0xFFF06292)), gray = neutral,
    )
    val error = contrastingColor(accents.red, highest, ink, 4.5)
    val errorContainer = lerp(high, accents.red, if (dark) .22f else .14f)
    val hair = ink.copy(alpha = if (dark) .14f else .12f)
    val rule = ink.copy(alpha = if (dark) .20f else .18f)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primary = ink, onPrimary = inverse,
        primaryContainer = highest, onPrimaryContainer = ink,
        inversePrimary = inverse,
        secondary = ink, onSecondary = inverse,
        secondaryContainer = high, onSecondaryContainer = ink,
        tertiary = muted, onTertiary = inverse,
        tertiaryContainer = high, onTertiaryContainer = ink,
        error = error, onError = inverse,
        errorContainer = errorContainer, onErrorContainer = ink,
        background = ground, onBackground = ink,
        surface = low, onSurface = ink,
        surfaceVariant = high, onSurfaceVariant = muted,
        surfaceTint = Color.Transparent,
        inverseSurface = ink, inverseOnSurface = inverse,
        outline = if (dark) Color(0xFF6F7175) else Color(0xFF8A8D91),
        outlineVariant = if (dark) Color(0xFF45464A) else Color(0xFFC4C6C9),
        scrim = Color.Black,
        surfaceBright = mid, surfaceDim = low,
        surfaceContainerLowest = lowest, surfaceContainerLow = low,
        surfaceContainer = mid, surfaceContainerHigh = high,
        surfaceContainerHighest = highest,
        primaryFixed = Color(0xFFE2E3E5), primaryFixedDim = Color(0xFFD3D4D6),
        onPrimaryFixed = Color(0xFF1C1E21), onPrimaryFixedVariant = Color(0xFF33363B),
        secondaryFixed = Color(0xFFE2E3E5), secondaryFixedDim = Color(0xFFD3D4D6),
        onSecondaryFixed = Color(0xFF1C1E21), onSecondaryFixedVariant = Color(0xFF33363B),
        tertiaryFixed = Color(0xFFE2E3E5), tertiaryFixedDim = Color(0xFFD3D4D6),
        onTertiaryFixed = Color(0xFF1C1E21), onTertiaryFixedVariant = Color(0xFF33363B),
    )
    return KainosPalette(scheme, accents, ground, mid, mid, hair, rule, dark)
}
