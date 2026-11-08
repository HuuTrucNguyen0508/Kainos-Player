package com.universalmusic.player.ui.theme

import androidx.compose.ui.text.font.FontFamily
import java.awt.GraphicsEnvironment

private val installedFonts by lazy {
    GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val desktopFamily by lazy {
    val family = listOf("Google Sans Flex", "Google Sans").firstOrNull { it in installedFonts }
    if (family == null) FontFamily.SansSerif else FontFamily(family)
}

internal actual fun caelestiaFontFamily(): FontFamily = desktopFamily

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val folioDisplayFamily by lazy {
    if ("Noto Serif" in installedFonts) FontFamily("Noto Serif") else FontFamily.Serif
}

internal actual fun folioDisplayFontFamily(): FontFamily = folioDisplayFamily
internal actual fun folioBodyFontFamily(): FontFamily = desktopFamily

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val frostFamily by lazy {
    if ("Fira Sans" in installedFonts) FontFamily("Fira Sans") else desktopFamily
}

internal actual fun frostFontFamily(): FontFamily = frostFamily
// Fontconfig files Fira Sans ExtraLight under the Fira Sans family, so the weight picks the cut.
internal actual fun frostThinFontFamily(): FontFamily = frostFamily
