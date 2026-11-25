package com.universalmusic.player.ui.theme

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import com.universalmusic.player.shared.R
import kotlin.math.roundToInt

internal actual fun caelestiaFontFamily(): FontFamily = FontFamily.SansSerif
internal actual fun folioDisplayFontFamily(): FontFamily = FontFamily.Serif
internal actual fun folioBodyFontFamily(): FontFamily = FontFamily.SansSerif
internal actual fun frostFontFamily(): FontFamily = FontFamily.SansSerif
internal actual fun frostThinFontFamily(): FontFamily = FontFamily.SansSerif

@OptIn(ExperimentalTextApi::class)
private val androidNyxFamily = FontFamily(
    Font(R.font.rubik, FontWeight.Normal, FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.rubik, FontWeight.Medium, FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.rubik, FontWeight.SemiBold, FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
    Font(R.font.rubik, FontWeight.Bold, FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.rubik, FontWeight.ExtraBold, FontStyle.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(800))),
)

internal actual fun nyxFontFamily(): FontFamily = androidNyxFamily
internal actual fun nyxCellPx(density: Density): Float = (3f * density.density).roundToInt().coerceAtLeast(3).toFloat()
internal actual val nyxTypeScale: Float = 1f
