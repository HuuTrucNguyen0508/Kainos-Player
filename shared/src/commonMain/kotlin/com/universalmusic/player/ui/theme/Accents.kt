package com.universalmusic.player.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

// Names identify semantic slots; the shell may harmonise them to other hues.
enum class AccentSlot { Red, Orange, Yellow, Green, Teal, Blue, Purple, Pink, Gray }
enum class AccentUse { Decor, Mark, Text }

data class AccentRamp(
    val red: Color, val orange: Color, val yellow: Color, val green: Color,
    val teal: Color, val blue: Color, val purple: Color, val pink: Color, val gray: Color,
) {
    operator fun get(slot: AccentSlot): Color = when (slot) {
        AccentSlot.Red -> red; AccentSlot.Orange -> orange; AccentSlot.Yellow -> yellow
        AccentSlot.Green -> green; AccentSlot.Teal -> teal; AccentSlot.Blue -> blue
        AccentSlot.Purple -> purple; AccentSlot.Pink -> pink; AccentSlot.Gray -> gray
    }

    fun forKey(key: String): Color {
        val hash = key.fold(0) { value, char -> value * 31 + char.code }
        return get(AccentSlot.entries[(hash.toUInt() % 8u).toInt()])
    }
}

data class KainosPalette(
    val scheme: ColorScheme,
    val accents: AccentRamp,
    val ground: Color, val sheet: Color, val tile: Color, val hair: Color, val rule: Color,
    val isDark: Boolean,
) {
    /** A quiet fill in the slot's hue, for active toggles and badges; pair it with [accent] `on` this. */
    fun accentContainer(slot: AccentSlot): Color = lerp(ground, accents[slot], if (isDark) .24f else .16f)

    fun accent(slot: AccentSlot, use: AccentUse = AccentUse.Decor, on: Color = ground): Color =
        if (use == AccentUse.Decor) accents[slot]
        else contrastingColor(accents[slot], on.compositeOver(ground), scheme.onSurface,
            if (use == AccentUse.Mark) 3.0 else 4.5)
}

fun contrastRatio(foreground: Color, background: Color): Double {
    val a = foreground.compositeOver(background).luminance().toDouble()
    val b = background.luminance().toDouble()
    return (maxOf(a, b) + .05) / (minOf(a, b) + .05)
}

internal fun contrastingColor(color: Color, on: Color, ink: Color, target: Double): Color {
    if (contrastRatio(color, on) >= target) return color
    val end = if (contrastRatio(ink, on) >= target) ink
        else if (contrastRatio(Color.Black, on) > contrastRatio(Color.White, on)) Color.Black else Color.White
    var low = 0f
    var high = 1f
    repeat(24) {
        val middle = (low + high) / 2
        if (contrastRatio(lerp(color, end, middle), on) >= target) high = middle else low = middle
    }
    return lerp(color, end, high)
}

object Accents {
    val spotify = AccentSlot.Green
    val youtube = AccentSlot.Red
    val local = AccentSlot.Teal
    val playlist = AccentSlot.Purple
    val folder = AccentSlot.Orange
    val availableLocal = AccentSlot.Green
    val cached = AccentSlot.Teal
    val downloading = AccentSlot.Blue
    val waiting = AccentSlot.Yellow
    val unavailable = AccentSlot.Gray
    val failed = AccentSlot.Red
    val home = AccentSlot.Orange
    val search = AccentSlot.Blue
    val library = AccentSlot.Teal
    val settings = AccentSlot.Purple
    val heart = AccentSlot.Pink
    val shuffle = AccentSlot.Purple
    val repeat = AccentSlot.Orange
    val sleep = AccentSlot.Yellow
    val cdQuality = AccentSlot.Teal
    val hiRes = AccentSlot.Yellow
    val lossy = AccentSlot.Blue
}

val SpotifyGreen: Color @Composable get() = LocalKainosPalette.current.accents[Accents.spotify]
val YoutubeRed: Color @Composable get() = LocalKainosPalette.current.accents[Accents.youtube]
val LocalSignal: Color @Composable get() = LocalKainosPalette.current.accents[Accents.local]

@Composable
fun providerColor(name: String): Color = LocalKainosPalette.current.accents[when (name) {
    "Spotify" -> Accents.spotify
    "YouTube Music" -> Accents.youtube
    "Local library" -> Accents.local
    else -> Accents.playlist
}]
