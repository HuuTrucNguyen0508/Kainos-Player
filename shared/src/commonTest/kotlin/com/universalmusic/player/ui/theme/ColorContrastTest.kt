package com.universalmusic.player.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.universalmusic.player.data.settings.AppColorScheme
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

class ColorContrastTest {
    @Test
    fun textOnSurfaceMeetsContrastAcrossSchemes() {
        val failures = mutableListOf<String>()
        for (scheme in AppColorScheme.entries) {
            for (dark in listOf(false, true)) {
                val colors = colorSchemeFor(scheme, dark)
                val mode = if (dark) "dark" else "light"
                check(failures, scheme, mode, "onSurface/surface", colors.onSurface, colors.surface)
                check(failures, scheme, mode, "onBackground/background", colors.onBackground, colors.background)
                check(failures, scheme, mode, "onPrimary/primary", colors.onPrimary, colors.primary)
            }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    private fun check(
        failures: MutableList<String>,
        scheme: AppColorScheme,
        mode: String,
        pair: String,
        foreground: Color,
        background: Color,
    ) {
        val ratio = contrastRatio(foreground, background)
        if (ratio < 4.5) {
            failures += "${scheme.name} $mode $pair ratio=$ratio"
        }
    }
}

internal fun contrastRatio(foreground: Color, background: Color): Double {
    val lighter = maxOf(relativeLuminance(foreground), relativeLuminance(background))
    val darker = minOf(relativeLuminance(foreground), relativeLuminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun relativeLuminance(color: Color): Double {
    val argb = color.toArgb()
    fun channel(shift: Int): Double {
        val value = ((argb shr shift) and 0xFF) / 255.0
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}
