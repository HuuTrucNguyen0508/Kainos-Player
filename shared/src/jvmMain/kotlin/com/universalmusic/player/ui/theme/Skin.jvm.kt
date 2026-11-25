package com.universalmusic.player.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Density
import java.awt.GraphicsEnvironment
import java.io.File

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

private val systemRubikFile: File? by lazy {
    listOf(
        "/usr/share/fonts/TTF/Rubik%5Bwght%5D.ttf",
        "/usr/share/fonts/TTF/Rubik[wght].ttf",
        "/usr/share/fonts/truetype/rubik/Rubik[wght].ttf",
    ).map(::File).firstOrNull { it.isFile && it.canRead() }
}

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private val nyxFamily by lazy {
    // Load the installed variable TTF with explicit weight axes so 400, 500 and 800 render cleanly
    // in Skiko even when AWT registers the family under "Rubik Light".
    val file = systemRubikFile
    when {
        file != null -> FontFamily(
            Font(file, FontWeight.Light, FontStyle.Normal, FontVariation.Settings(FontVariation.weight(300))),
            Font(file, FontWeight.Normal, FontStyle.Normal, FontVariation.Settings(FontVariation.weight(400))),
            Font(file, FontWeight.Medium, FontStyle.Normal, FontVariation.Settings(FontVariation.weight(500))),
            Font(file, FontWeight.SemiBold, FontStyle.Normal, FontVariation.Settings(FontVariation.weight(600))),
            Font(file, FontWeight.Bold, FontStyle.Normal, FontVariation.Settings(FontVariation.weight(700))),
            Font(file, FontWeight.ExtraBold, FontStyle.Normal, FontVariation.Settings(FontVariation.weight(800))),
            Font(file, FontWeight.Black, FontStyle.Normal, FontVariation.Settings(FontVariation.weight(900))),
        )
        installedFonts.any { it.equals("Rubik", ignoreCase = true) || it.startsWith("Rubik ") } -> FontFamily("Rubik")
        else -> desktopFamily
    }
}

internal actual fun nyxFontFamily(): FontFamily = nyxFamily
internal actual fun nyxCellPx(density: Density): Float = 5f
