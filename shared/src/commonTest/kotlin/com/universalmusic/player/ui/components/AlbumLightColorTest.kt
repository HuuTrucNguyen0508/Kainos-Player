package com.universalmusic.player.ui.components

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlbumLightColorTest {
    private fun close(expected: Float, actual: Float, tolerance: Float = 0.02f) =
        assertTrue(abs(expected - actual) <= tolerance, "expected $expected but was $actual")

    @Test
    fun hslRoundTripsThroughRgb() {
        listOf(Color(0.8f, 0.2f, 0.3f), Color(0.1f, 0.6f, 0.4f), Color(0.3f, 0.3f, 0.9f)).forEach { color ->
            val (h, s, l) = color.toHsl()
            val back = hslColor(h, s, l)
            close(color.red, back.red)
            close(color.green, back.green)
            close(color.blue, back.blue)
        }
    }

    @Test
    fun glowIsDeeperOnDarkSchemesAndPastelOnLightOnes() {
        val vivid = Color(0.9f, 0.1f, 0.2f)
        val onDark = albumLightColor(vivid, darkColorScheme())
        val onLight = albumLightColor(vivid, lightColorScheme())
        close(0.50f, onDark.toHsl().third)
        close(0.70f, onLight.toHsl().third)
        // Hue survives the clamp; saturation is capped so the light never reads as paint.
        close(vivid.toHsl().first, onDark.toHsl().first, tolerance = 2f)
        assertTrue(onDark.toHsl().second <= 0.69f)
    }

    @Test
    fun greyCoversBorrowTheSchemeAccentHue() {
        val scheme = darkColorScheme(primary = Color(0.2f, 0.4f, 0.9f))
        val glow = albumLightColor(Color(0.5f, 0.5f, 0.5f), scheme)
        close(scheme.primary.toHsl().first, glow.toHsl().first, tolerance = 2f)
    }

    @Test
    fun darkFlagFollowsTheSchemeBackground() {
        assertEquals(true, darkColorScheme().isDark)
        assertEquals(false, lightColorScheme().isDark)
    }
}
