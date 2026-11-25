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
        for (desktopEnv in listOf(null, Skin.FOLIO, Skin.CAELESTIA, Skin.FROST, Skin.NYX)) {
            assertEquals(desktopEnv ?: Skin.CAELESTIA, resolveSkin(SkinChoice.PLATFORM_DEFAULT, "Linux", desktopEnv))
            assertEquals(Skin.FOLIO, resolveSkin(SkinChoice.FOLIO, "Linux", desktopEnv))
            assertEquals(Skin.CAELESTIA, resolveSkin(SkinChoice.CAELESTIA, "Linux", desktopEnv))
            assertEquals(Skin.FROST, resolveSkin(SkinChoice.FROST, "Linux", desktopEnv))
            assertEquals(Skin.NYX, resolveSkin(SkinChoice.NYX, "Linux", desktopEnv))
            assertEquals(Skin.FOLIO, resolveSkin(SkinChoice.PLATFORM_DEFAULT, "Android", desktopEnv))
            assertEquals(Skin.NYX, resolveSkin(SkinChoice.NYX, "Android", desktopEnv))
        }
    }

    @Test fun nyxIsDarkOnlyAndFallsBackToFrostLightWhenDesktopIsLight() {
        val darkShell = CaelestiaScheme(isDark = true, palette = folioPalette(FolioTint.CATPPUCCIN, true))
        val lightShell = CaelestiaScheme(isDark = false, palette = folioPalette(FolioTint.CATPPUCCIN, false))
        val settings = AppSettings()

        // Desktop Nyx in dark mode -> Nyx skin + Nyx palette.
        val darkDesktop = resolveTheme(settings, systemDark = false, scheme = darkShell, platform = "Linux", desktopEnv = Skin.NYX)
        assertEquals(Skin.NYX, darkDesktop.skin)
        assertPaletteEquals(nyxPalette(), darkDesktop.palette)
        assertTrue(darkDesktop.followsCaelestia)

        // Desktop Nyx in light mode (via light desktop scheme) -> Frost Light.
        val lightDesktop = resolveTheme(settings, systemDark = true, scheme = lightShell, platform = "Linux", desktopEnv = Skin.NYX)
        assertEquals(Skin.FROST, lightDesktop.skin)
        assertPaletteEquals(frostPalette(false), lightDesktop.palette)
        assertTrue(lightDesktop.followsCaelestia)

        // Desktop Nyx when not following shell scheme and user sets Light -> Frost Light.
        val lightLocal = resolveTheme(
            settings.copy(followCaelestiaScheme = false, themeMode = ThemeMode.LIGHT),
            systemDark = true,
            scheme = darkShell,
            platform = "Linux",
            desktopEnv = Skin.NYX,
        )
        assertEquals(Skin.FROST, lightLocal.skin)
        assertPaletteEquals(frostPalette(false), lightLocal.palette)
        assertFalse(lightLocal.followsCaelestia)

        // Explicit SkinChoice.NYX stays Nyx dark regardless of desktop or app light mode.
        val explicitLinux = resolveTheme(
            settings.copy(skin = SkinChoice.NYX, themeMode = ThemeMode.LIGHT),
            systemDark = false,
            scheme = lightShell,
            platform = "Linux",
            desktopEnv = Skin.FOLIO,
        )
        assertEquals(Skin.NYX, explicitLinux.skin)
        assertPaletteEquals(nyxPalette(), explicitLinux.palette)
        assertFalse(explicitLinux.followsCaelestia)

        val explicitAndroid = resolveTheme(
            settings.copy(skin = SkinChoice.NYX, themeMode = ThemeMode.LIGHT),
            systemDark = false,
            scheme = null,
            platform = "Android",
        )
        assertEquals(Skin.NYX, explicitAndroid.skin)
        assertPaletteEquals(nyxPalette(), explicitAndroid.palette)
        assertFalse(explicitAndroid.followsCaelestia)
    }

    @Test fun nyxTokensAndPaletteContrastMeetDesignSpec() {
        val tokens = skinTokens(Skin.NYX)
        assertEquals(Skin.NYX, tokens.skin)
        assertEquals(SeekStyle.CELLS, tokens.seek)
        assertEquals(GroundStyle.SKY, tokens.ground)
        assertEquals(nyxFontFamily(), tokens.display)
        assertEquals(80f, tokens.typography.displayLarge.fontSize.value)
        assertEquals(22f, tokens.typography.headlineSmall.fontSize.value)
        assertEquals(14f, tokens.typography.titleSmall.fontSize.value)
        assertEquals(13.5f, tokens.typography.bodyMedium.fontSize.value)
        assertEquals(11.5f, tokens.typography.labelSmall.fontSize.value)

        val scheme = nyxPalette().scheme
        for (ground in listOf(
            scheme.background,
            scheme.surface,
            scheme.surfaceContainerLow,
            scheme.surfaceContainer,
            scheme.surfaceContainerHigh,
            scheme.surfaceContainerHighest,
        )) {
            assertTrue(contrastRatio(scheme.onSurface, ground) >= 4.5, "onSurface on $ground")
            assertTrue(contrastRatio(scheme.onSurfaceVariant, ground) >= 4.5, "onSurfaceVariant on $ground")
        }
        assertTrue(contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5)
        assertTrue(contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer) >= 4.5)
        assertTrue(contrastRatio(scheme.onSecondary, scheme.secondary) >= 4.5)
        assertTrue(contrastRatio(scheme.onSecondaryContainer, scheme.secondaryContainer) >= 4.5)
        assertTrue(contrastRatio(scheme.onTertiary, scheme.tertiary) >= 4.5)
        assertTrue(contrastRatio(scheme.onTertiaryContainer, scheme.tertiaryContainer) >= 4.5)
        assertTrue(contrastRatio(scheme.onError, scheme.error) >= 4.5)
        assertTrue(contrastRatio(scheme.onErrorContainer, scheme.errorContainer) >= 4.5)
        assertTrue(contrastRatio(scheme.inverseOnSurface, scheme.inverseSurface) >= 4.5)
        assertTrue(contrastRatio(scheme.inversePrimary, scheme.inverseSurface) >= 4.5)
        assertTrue(contrastRatio(scheme.outline, scheme.surfaceContainerHighest) >= 3.0)
    }

    @Test fun nyxRubikRendersDistinctWeightsOnDesktop() {
        fun inkPixels(weight: androidx.compose.ui.text.font.FontWeight): Int {
            val scene = androidx.compose.ui.ImageComposeScene(width = 320, height = 100, density = androidx.compose.ui.unit.Density(1f)) {
                androidx.compose.material3.Text(
                    "Library",
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = nyxFontFamily(),
                        fontSize = androidx.compose.ui.unit.TextUnit(56f, androidx.compose.ui.unit.TextUnitType.Sp),
                        fontWeight = weight,
                        color = androidx.compose.ui.graphics.Color.White,
                    ),
                )
            }
            return try {
                scene.render(0).use { image ->
                    val png = image.encodeToData()!!.use { data ->
                        javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(data.bytes))
                    }
                    val pixels = png.getRGB(0, 0, png.width, png.height, null, 0, png.width)
                    pixels.count { ((it ushr 16) and 0xFF) > 128 }
                }
            } finally {
                scene.close()
            }
        }
        val regular = inkPixels(androidx.compose.ui.text.font.FontWeight.Normal)
        val medium = inkPixels(androidx.compose.ui.text.font.FontWeight.Medium)
        val extraBold = inkPixels(androidx.compose.ui.text.font.FontWeight.ExtraBold)
        assertTrue(regular > 200, "Rubik 400 rendered $regular ink pixels")
        assertTrue(medium > regular, "Rubik 500 ($medium) should be heavier than 400 ($regular)")
        assertTrue(extraBold > medium, "Rubik 800 ($extraBold) should be heavier than 500 ($medium)")
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

    @Test fun frostTextIsCaelestiaSized() {
        fun sizes(skin: Skin) = with(skinTokens(skin).typography) {
            listOf(
                displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall,
                titleLarge, titleMedium, titleSmall, bodyLarge, bodyMedium, bodySmall,
                labelLarge, labelMedium, labelSmall,
            ).map { it.fontSize }
        }
        assertEquals(sizes(Skin.CAELESTIA), sizes(Skin.FROST))
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
