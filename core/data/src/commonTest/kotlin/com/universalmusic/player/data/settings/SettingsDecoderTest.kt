package com.universalmusic.player.data.settings

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SettingsDecoderTest {
    @Test fun legacySchemesKeepOtherSettings() {
        for (legacy in listOf("DRACULA", "OLIVE", "CAELESTIA", "ONE_DARK", "FUTURE")) {
            val decoded = decodeAppSettings("""{"colorScheme":"$legacy","dynamicColor":true,"compactMode":true,"playbackVolume":0.42}""")
            assertEquals(FolioTint.FOLIO, decoded.tint)
            assertTrue(decoded.compactMode)
            assertEquals(.42f, decoded.playbackVolume)
        }
        for (tint in FolioTint.entries) {
            assertEquals(tint, decodeAppSettings("""{"colorScheme":"${tint.name}"}""").tint)
        }
    }

    @Test fun unknownNewEnumsDefaultWithoutResettingOtherFields() {
        val decoded = decodeAppSettings("""{"skin":"future","tint":"future","spotifyClientId":"keep","libraryFavoritesOnly":true}""")
        assertEquals(SkinChoice.PLATFORM_DEFAULT, decoded.skin)
        assertEquals(FolioTint.FOLIO, decoded.tint)
        assertEquals("keep", decoded.spotifyClientId)
        assertTrue(decoded.libraryFavoritesOnly)
    }

    @Test fun explicitTintTakesPrecedenceOverLegacy() {
        assertEquals(FolioTint.NORD, decodeAppSettings("""{"colorScheme":"CATPPUCCIN","tint":"NORD"}""").tint)
        assertEquals(FolioTint.FOLIO, decodeAppSettings("""{"colorScheme":"CATPPUCCIN","tint":"unknown"}""").tint)
    }

    @Test fun newFieldsSurviveWriteRead() {
        val original = AppSettings(skin = SkinChoice.CAELESTIA, tint = FolioTint.ROSE_PINE,
            themeMode = ThemeMode.DARK, followCaelestiaScheme = false, localMusicFolders = listOf("/music"))
        assertEquals(original, decodeAppSettings(Json { encodeDefaults = true }.encodeToString(original)))
    }
}
