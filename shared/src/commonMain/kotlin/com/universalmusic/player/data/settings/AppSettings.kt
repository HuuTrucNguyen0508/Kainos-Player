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
    /**
     * Spotify playlist id/URL/URI for Discover Weekly when the Web API cannot list it.
     * Desktop librespot fetches algorithmic playlists by this id.
     */
    val spotifyDiscoverWeeklyPlaylistId: String? = null,
    val youtubeDataApiKey: String? = null,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val colorScheme: AppColorScheme = AppColorScheme.OLIVE,
    val dynamicColor: Boolean = true,
    val compactMode: Boolean = false,
    val sourceSelection: SourceSelectionMode = SourceSelectionMode.AUTOMATIC,
    val crossfadeMs: Int = 0,
    val gapless: Boolean = true,
    val normalizeVolume: Boolean = false,
    /**
     * Absolute paths (desktop) or tree URIs (Android SAF) for local music roots.
     * Interpreted with [localMusicFoldersConfigured].
     */
    val localMusicFolders: List<String> = emptyList(),
    /**
     * When false, empty [localMusicFolders] means use the platform default (~/Music on desktop).
     * When true, empty means the user chose no folders — do not reintroduce defaults.
     */
    val localMusicFoldersConfigured: Boolean = false,
    /** Android: include MediaStore music in addition to SAF roots (deduped). Desktop ignores. */
    val includeMediaStoreLibrary: Boolean = true,
    val librarySongSort: TrackSort = TrackSort.NAME_ASCENDING,
    /** When true, Library shows only scanned local files (no Spotify liked songs, saved tracks, or playlists). */
    val libraryLocalOnly: Boolean = false,
    /** When true, Library Songs (and related album views) show only app-hearted tracks. */
    val libraryFavoritesOnly: Boolean = false,
    /**
     * When true, a Search-started queue may append one continuation batch after the last track.
     * Default is off so playback stops at the end of the Search results unless the user opts in.
     */
    val searchAutoplayEnabled: Boolean = false,
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
