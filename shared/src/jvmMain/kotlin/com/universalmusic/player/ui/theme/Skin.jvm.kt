package com.universalmusic.player.ui.theme

import androidx.compose.ui.text.font.FontFamily
import java.awt.GraphicsEnvironment

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val desktopFamily by lazy {
    val installed = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames.toSet()
    val family = listOf("Google Sans Flex", "Google Sans").firstOrNull { it in installed }
    if (family == null) FontFamily.SansSerif else FontFamily(family)
}

internal actual fun caelestiaFontFamily(): FontFamily = desktopFamily
