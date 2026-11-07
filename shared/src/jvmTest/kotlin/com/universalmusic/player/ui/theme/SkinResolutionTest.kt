package com.universalmusic.player.ui.theme

import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.FolioTint
import com.universalmusic.player.data.settings.SkinChoice
import com.universalmusic.player.data.settings.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SkinResolutionTest {
    @Test fun platformDefaultDoesNotDependOnWindowWidth() {
        assertEquals(Skin.FOLIO, resolveSkin(SkinChoice.PLATFORM_DEFAULT, "Android"))
        assertEquals(Skin.CAELESTIA, resolveSkin(SkinChoice.PLATFORM_DEFAULT, "Linux"))
        assertEquals(Skin.FOLIO, resolveSkin(SkinChoice.FOLIO, "Linux"))
        assertEquals(Skin.CAELESTIA, resolveSkin(SkinChoice.CAELESTIA, "Android"))
    }

    @Test fun desktopDefaultFollowsDesktopEnvWhileExplicitChoicesAndAndroidIgnoreIt() {
        for (desktopEnv in listOf(null, Skin.FOLIO, Skin.CAELESTIA)) {
            assertEquals(desktopEnv ?: Skin.CAELESTIA, resolveSkin(SkinChoice.PLATFORM_DEFAULT, "Linux", desktopEnv))
            assertEquals(Skin.FOLIO, resolveSkin(SkinChoice.FOLIO, "Linux", desktopEnv))
            assertEquals(Skin.CAELESTIA, resolveSkin(SkinChoice.CAELESTIA, "Linux", desktopEnv))
            assertEquals(Skin.FOLIO, resolveSkin(SkinChoice.PLATFORM_DEFAULT, "Android", desktopEnv))
        }
    }

    @Test fun frostIsGreyGlassThatOnlyTakesLightOrDarkFromTheDesktop() {
        val settings = AppSettings()
        val dark = CaelestiaScheme(isDark = true, palette = folioPalette(FolioTint.CATPPUCCIN, true))
        val desktop = resolveTheme(settings, false, dark, "Linux", Skin.FROST)
        assertEquals(Skin.FROST, desktop.skin)
        assertPaletteEquals(frostPalette(true), desktop.palette)
        assertTrue(desktop.followsCaelestia)
        val chosen = resolveTheme(settings.copy(skin = SkinChoice.FROST, followCaelestiaScheme = false, themeMode = ThemeMode.LIGHT), true, dark, "Linux")
        assertPaletteEquals(frostPalette(false), chosen.palette)
        assertEquals(Skin.FROST, resolveSkin(SkinChoice.FROST, "Android"))
        // Every Frost ink reads at 4.5:1 on the greys it sits on.
        for (isDark in listOf(false, true)) {
            val scheme = frostPalette(isDark).scheme
            for (ground in listOf(scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)) {
                assertTrue(contrastRatio(scheme.onSurface, ground) >= 4.5)
                assertTrue(contrastRatio(scheme.onSurfaceVariant, ground) >= 4.5, "variant on $ground dark=$isDark")
            }
            assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5)
            assertTrue(contrastRatio(scheme.outline, scheme.surfaceContainerHighest) >= 3.0, "outline dark=$isDark")
        }
    }

    @Test fun frostTakesItsModeFromALightShellSchemeToo() {
        val light = CaelestiaScheme(isDark = false, palette = folioPalette(FolioTint.CATPPUCCIN, false))
        val settings = AppSettings(skin = SkinChoice.FROST, themeMode = ThemeMode.DARK)
        val resolved = resolveTheme(settings, true, light, "Linux")
        assertPaletteEquals(frostPalette(false), resolved.palette)
        assertTrue(resolved.followsCaelestia)
    }

    @Test fun frostHasNoHueOutsideFailure() {
        for (isDark in listOf(false, true)) {
            val palette = frostPalette(isDark)
            for (slot in AccentSlot.entries) {
                val color = palette.accents[slot]
                assertTrue(maxOf(color.red, color.green, color.blue) - minOf(color.red, color.green, color.blue) < .04f, "$slot dark=$isDark")
                assertEquals(palette.accents.gray, color, "$slot dark=$isDark")
            }
        }
    }

    @Test fun matchDesktopFolioUsesShellColoursOnlyWhileFollowing() {
        val settings = AppSettings(tint = FolioTint.CATPPUCCIN, themeMode = ThemeMode.LIGHT)
        val shell = assertNotNull(parseCaelestiaScheme(schemeFixture()))
        val following = resolveTheme(settings, false, shell, "Linux", Skin.FOLIO)
        assertEquals(Skin.FOLIO, following.skin)
        assertEquals(shell.palette, following.palette)
        assertTrue(following.followsCaelestia)
        val saved = resolveTheme(settings.copy(followCaelestiaScheme = false), false, shell, "Linux", Skin.FOLIO)
        assertEquals(Skin.FOLIO, saved.skin)
        assertFalse(saved.followsCaelestia)
        assertPaletteEquals(folioPalette(settings.tint, false), saved.palette)
        val explicit = resolveTheme(settings.copy(skin = SkinChoice.FOLIO), false, shell, "Linux", Skin.FOLIO)
        assertFalse(explicit.followsCaelestia)
        assertPaletteEquals(folioPalette(settings.tint, false), explicit.palette)
    }

    @Test fun folioDesktopBodySharesCaelestiaSans() {
        val folio = skinTokens(Skin.FOLIO)
        assertEquals(folioDisplayFontFamily(), folio.display)
        assertEquals(caelestiaFontFamily(), folio.typography.bodyLarge.fontFamily)
        assertEquals(folio.display, folio.typography.headlineLarge.fontFamily)
        assertEquals(GroundStyle.DOTTED_PAPER, folio.ground)
        assertEquals(SeekStyle.RULED, folio.seek)
    }

    @Test fun validShellSchemeOwnsModeWhileFollowing() {
        val settings = AppSettings(themeMode = ThemeMode.LIGHT)
        val dark = assertNotNull(parseCaelestiaScheme(schemeFixture()))
        val light = assertNotNull(parseCaelestiaScheme(schemeFixture().replace("\"dark\"", "\"light\"")))
        assertEquals(dark.palette, resolveTheme(settings, false, dark, "Linux").palette)
        assertTrue(resolveTheme(settings, false, dark, "Linux").followsCaelestia)
        assertTrue(resolveTheme(settings, false, dark, "Linux").palette.isDark)
        assertFalse(resolveTheme(settings.copy(themeMode = ThemeMode.DARK), true, light, "Linux").palette.isDark)
    }

    private fun assertPaletteEquals(expected: KainosPalette, actual: KainosPalette) {
        assertEquals(expected.isDark, actual.isDark)
        assertEquals(expected.ground, actual.ground)
        assertEquals(expected.sheet, actual.sheet)
        assertEquals(expected.tile, actual.tile)
        assertEquals(expected.accents, actual.accents)
        assertEquals(expected.scheme.primary, actual.scheme.primary)
        assertEquals(expected.scheme.onSurface, actual.scheme.onSurface)
    }

    @Test fun fallbackUsesSavedTintAndMode() {
        val shell = assertNotNull(parseCaelestiaScheme(schemeFixture()))
        for (mode in ThemeMode.entries) for (systemDark in listOf(false, true)) {
            val settings = AppSettings(tint = FolioTint.CATPPUCCIN, themeMode = mode)
            val expected = folioPalette(FolioTint.CATPPUCCIN, mode == ThemeMode.DARK || mode == ThemeMode.SYSTEM && systemDark)
            assertPaletteEquals(expected, resolveTheme(settings, systemDark, null, "Linux").palette)
            assertPaletteEquals(expected, resolveTheme(settings.copy(followCaelestiaScheme = false), systemDark, shell, "Linux").palette)
            assertPaletteEquals(expected, resolveTheme(settings.copy(skin = SkinChoice.FOLIO), systemDark, shell, "Linux").palette)
            assertPaletteEquals(expected, resolveTheme(settings.copy(skin = SkinChoice.CAELESTIA), systemDark, shell, "Android").palette)
        }
    }
}
