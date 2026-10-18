package com.universalmusic.player.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.universalmusic.player.data.settings.FolioTint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AccentContrastTest {
    @Test fun semanticAccentsMeetContrastOnTheirActualContainers() {
        val palettes = FolioTint.entries.flatMap { tint -> listOf(false, true).map { dark ->
            "$tint/$dark" to folioPalette(tint, dark)
        } } + ("caelestia" to assertNotNull(parseCaelestiaScheme(schemeFixture())).palette)
        for ((name, palette) in palettes) {
            val scheme = palette.scheme
            val backgrounds = listOf(palette.ground, palette.sheet, palette.tile, scheme.surface, scheme.surfaceVariant,
                scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest, scheme.primary, scheme.primaryContainer, scheme.secondaryContainer, scheme.tertiaryContainer,
                scheme.primary.copy(alpha = .12f).compositeOver(palette.sheet),
                scheme.onSurface.copy(alpha = .08f).compositeOver(palette.tile))
            for (slot in AccentSlot.entries) {
                assertEquals(palette.accents[slot], palette.accent(slot, AccentUse.Decor))
                for (background in backgrounds) for ((use, ratio) in listOf(AccentUse.Mark to 3.0, AccentUse.Text to 4.5)) {
                    assertTrue(contrastRatio(palette.accent(slot, use, background), background) >= ratio - .00001,
                        "$name $slot $use on $background")
                }
                val translucent = scheme.primary.copy(alpha = .12f)
                assertTrue(contrastRatio(palette.accent(slot, AccentUse.Text, translucent), translucent.compositeOver(palette.ground)) >= 4.5 - .00001)
            }
            val bodyBackgrounds = listOf(palette.ground, palette.sheet, palette.tile, scheme.surfaceVariant,
                scheme.surfaceContainerLowest, scheme.surfaceContainerLow, scheme.surfaceContainer,
                scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)
            for (background in bodyBackgrounds) {
                for (ink in listOf(scheme.onSurface, scheme.onSurfaceVariant)) {
                    assertTrue(contrastRatio(ink, background) >= 4.5 - .00001,
                        "$name body $ink on $background = ${contrastRatio(ink, background)}")
                }
            }
            if (name != "caelestia") for (role in listOf(scheme.primary, scheme.secondary, scheme.tertiary, scheme.error)) {
                for (background in bodyBackgrounds) assertTrue(contrastRatio(role, background) >= 4.5 - .00001,
                    "$name M3 text role $role on $background")
            }
            listOf(scheme.onSurface to scheme.surface, scheme.onBackground to scheme.background,
                scheme.onPrimary to scheme.primary, scheme.onSurfaceVariant to scheme.surfaceVariant).forEach { (ink, on) ->
                assertTrue(contrastRatio(ink, on) >= 4.5 - .00001, "$name body $ink on $on = ${contrastRatio(ink, on)}")
            }
        }
    }

    @Test fun forKeyIsStableAndUsesOnlyChromaticSlots() {
        val ramp = folioPalette(FolioTint.FOLIO, false).accents
        assertEquals(ramp.orange, ramp.forKey("a"))
        assertEquals(ramp.red, ramp.forKey(""))
        repeat(1000) { key -> assertTrue(ramp.forKey("track-$key") in AccentSlot.entries.take(8).map { ramp[it] }) }
    }

    @Test fun rawYellowStaysYellowOnlyForDecorationOnLightPaper() {
        val palette = folioPalette(FolioTint.FOLIO, false)
        assertEquals(Color(0xFFF5B800), palette.accent(AccentSlot.Yellow, AccentUse.Decor))
        assertTrue(contrastRatio(palette.accents.yellow, palette.ground) < 3)
        assertTrue(contrastRatio(palette.accent(AccentSlot.Yellow, AccentUse.Mark), palette.ground) >= 3)
        assertTrue(contrastRatio(palette.accent(AccentSlot.Yellow, AccentUse.Text), palette.ground) >= 4.5)
    }
}
