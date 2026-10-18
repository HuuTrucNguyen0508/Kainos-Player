package com.universalmusic.player.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.universalmusic.player.data.settings.FolioTint

fun folioPalette(tint: FolioTint, dark: Boolean): KainosPalette {
    val pair = folioTints.getValue(tint)
    val raw = if (dark) pair.second else pair.first
    fun container(hue: Color) = lerp(raw.sheet, hue, if (dark) .22f else .12f)
    val highest = lerp(raw.field, raw.ink, .06f)
    val surfaces = listOf(raw.paper, raw.sheet, raw.field, raw.tile, highest,
        container(raw.accent), container(raw.hues.teal), container(raw.hues.purple), container(raw.hues.red))
    val textGround = if (dark) surfaces.maxBy { it.luminance() } else surfaces.minBy { it.luminance() }
    val ink = contrastingColor(raw.ink, textGround, raw.ink, 4.5)
    fun text(on: Color) = contrastingColor(ink, on, ink, 4.5)
    fun textAccent(hue: Color) = contrastingColor(hue, textGround, ink, 4.5)
    val primary = textAccent(raw.accent)
    val secondary = textAccent(raw.hues.teal)
    val tertiary = textAccent(raw.hues.purple)
    val error = textAccent(raw.hues.red)
    val fixed = pair.first
    fun fixedContainer(hue: Color, amount: Float) = lerp(fixed.sheet, hue, amount)
    fun fixedText(hue: Color, amount: Float) = contrastingColor(fixed.ink, fixedContainer(hue, amount), fixed.ink, 4.5)
    val base = if (dark) darkColorScheme() else lightColorScheme()
    val scheme = base.copy(
        primaryFixed = fixedContainer(fixed.accent, .12f), primaryFixedDim = fixedContainer(fixed.accent, .22f),
        onPrimaryFixed = fixedText(fixed.accent, .22f), onPrimaryFixedVariant = fixedText(fixed.accent, .22f),
        secondaryFixed = fixedContainer(fixed.hues.teal, .12f), secondaryFixedDim = fixedContainer(fixed.hues.teal, .22f),
        onSecondaryFixed = fixedText(fixed.hues.teal, .22f), onSecondaryFixedVariant = fixedText(fixed.hues.teal, .22f),
        tertiaryFixed = fixedContainer(fixed.hues.purple, .12f), tertiaryFixedDim = fixedContainer(fixed.hues.purple, .22f),
        onTertiaryFixed = fixedText(fixed.hues.purple, .22f), onTertiaryFixedVariant = fixedText(fixed.hues.purple, .22f),
        primary = primary, onPrimary = text(primary),
        primaryContainer = container(raw.accent), onPrimaryContainer = text(container(raw.accent)),
        inversePrimary = contrastingColor(raw.accent, ink, raw.paper, 4.5),
        secondary = secondary, onSecondary = text(secondary),
        secondaryContainer = container(raw.hues.teal), onSecondaryContainer = text(container(raw.hues.teal)),
        tertiary = tertiary, onTertiary = text(tertiary),
        tertiaryContainer = container(raw.hues.purple), onTertiaryContainer = text(container(raw.hues.purple)),
        error = error, onError = text(error),
        errorContainer = container(raw.hues.red), onErrorContainer = text(container(raw.hues.red)),
        background = raw.paper, onBackground = ink,
        surface = raw.sheet, onSurface = text(raw.sheet),
        surfaceVariant = raw.field, onSurfaceVariant = contrastingColor(raw.muted, textGround, ink, 4.5),
        surfaceTint = primary, inverseSurface = ink, inverseOnSurface = text(ink),
        outline = raw.rule, outlineVariant = raw.hair, scrim = Color.Black,
        surfaceBright = if (dark) raw.field else raw.tile,
        surfaceDim = if (dark) raw.paper else raw.sheet,
        surfaceContainerLowest = raw.paper, surfaceContainerLow = raw.sheet,
        surfaceContainer = raw.tile, surfaceContainerHigh = raw.field,
        surfaceContainerHighest = highest,
    )
    return KainosPalette(scheme, raw.hues, raw.paper, raw.sheet, raw.tile, raw.hair, raw.rule, dark)
}
