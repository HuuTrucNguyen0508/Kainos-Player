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
enum class AppColorScheme(val label: String, val description: String) {
    OLIVE("Olive Grove", "Your current Caelestia dynamic olive"),
    CAELESTIA("Caelestia Teal", "Default Caelestia shell teal"),
    CATPPUCCIN("Catppuccin", "Mocha / Latte"),
    NORD("Nord", "Arctic blues"),
    GRUVBOX("Gruvbox", "Warm retro"),
    ROSE_PINE("Rosé Pine", "Soft purple / dawn"),
    TOKYO_NIGHT("Tokyo Night", "Neon city blue"),
    DRACULA("Dracula", "Classic purple dark"),
    EVERFOREST("Everforest", "Muted forest green"),
    ONE_DARK("One Dark", "Atom-style"),
}

@Serializable
data class AppSettings(
    val spotifyClientId: String? = null,
    val spotifyPlaybackDeviceId: String? = null,
    val spotifyPlaybackDeviceName: String? = null,
    val youtubeDataApiKey: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorScheme: AppColorScheme = AppColorScheme.OLIVE,
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
    /** When true, Library shows only scanned local files (no Spotify liked songs, saved, samples, or playlists). */
    val libraryLocalOnly: Boolean = false,
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
