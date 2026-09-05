package com.universalmusic.player.data.settings

import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.domain.model.TrackSort
import kotlinx.serialization.Serializable

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val compactMode: Boolean = false,
    val sourceSelection: SourceSelectionMode = SourceSelectionMode.AUTOMATIC,
    val crossfadeMs: Int = 0,
    val gapless: Boolean = true,
    val normalizeVolume: Boolean = false,
    val sampleCatalogEnabled: Boolean = true,
    /** Absolute folder paths scanned for local audio on desktop. Empty means default to ~/Music. */
    val localMusicFolders: List<String> = emptyList(),
    val librarySongSort: TrackSort = TrackSort.NAME_ASCENDING,
) {
    fun toPlaybackPreferences(): PlaybackPreferences = PlaybackPreferences(
        sourceSelection = sourceSelection,
        crossfadeMs = crossfadeMs,
        gapless = gapless,
        normalizeVolume = normalizeVolume,
    )
}

interface SettingsStore {
    suspend fun read(): AppSettings
    suspend fun write(settings: AppSettings)
}
