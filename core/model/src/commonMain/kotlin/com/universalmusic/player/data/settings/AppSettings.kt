package com.universalmusic.player.data.settings

import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.domain.model.TrackSort
import com.universalmusic.player.data.sync.PairedPeer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Serializable
enum class SkinChoice { PLATFORM_DEFAULT, FOLIO, CAELESTIA }

@Serializable
enum class FolioTint { FOLIO, EVERFOREST, CATPPUCCIN, GRUVBOX, NORD, ROSE_PINE, TOKYO_NIGHT }

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
    val skin: SkinChoice = SkinChoice.PLATFORM_DEFAULT,
    val tint: FolioTint = FolioTint.FOLIO,
    val followCaelestiaScheme: Boolean = true,
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
    /** In-app playback gain 0–1 (desktop mpv / Android ExoPlayer). Default full. */
    val playbackVolume: Float = 1f,
    /** Home LAN hearts sync (phone client ↔ desktop hub). */
    val homeLanSyncEnabled: Boolean = false,
    val homeLanSyncRole: com.universalmusic.player.data.sync.HomeLanSyncRole =
        com.universalmusic.player.data.sync.HomeLanSyncRole.AUTO,
    val homeLanSyncDeviceId: String? = null,
    /** The hub this device syncs with, trusted by the fingerprint it showed while pairing. */
    val homeLanSyncHub: PairedPeer? = null,
    /** Devices this hub trusts. */
    val homeLanSyncPeers: List<PairedPeer> = emptyList(),
    /** Blocks trust use until identity reset finishes or verified old-key recovery commits. */
    val homeLanSyncIdentityResetPending: Boolean = false,
    /** Set when a shared-secret pairing was dropped; Settings asks the user to pair again. */
    val homeLanSyncRepairNeeded: Boolean = false,
    /** A shared-secret pairing from before per-device keys, read only so it can be dropped. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("homeLanSyncPairing")
    val legacyHomeLanSyncPairing: JsonElement? = null,
    val homeLanSyncVaultFolder: String? = null,
    /**
     * When true, vault blob sync only transfers files whose basename matches an app-hearted
     * local library track (not the whole vault folder). Hearts ops still sync fully.
     */
    val homeLanSyncVaultHeartsOnly: Boolean = true,
    /** Desktop: write ~/.config/autostart entry for --hub-only. */
    val homeLanSyncHubAutostart: Boolean = false,
    val homeLanSyncLastAtMs: Long? = null,
    val homeLanSyncLastError: String? = null,
    val homeLanSyncLastDetail: String? = null,
) {
    fun toPlaybackPreferences(): PlaybackPreferences = PlaybackPreferences(
        sourceSelection = sourceSelection,
        crossfadeMs = crossfadeMs,
        gapless = gapless,
        normalizeVolume = normalizeVolume,
    )
}

fun AppSettings.withoutLegacyHomeLanPairing(): AppSettings =
    if (legacyHomeLanSyncPairing == null) this
    else copy(legacyHomeLanSyncPairing = null, homeLanSyncRepairNeeded = true)

interface SettingsStore {
    suspend fun read(): AppSettings
    /** Returns only after settings have been persisted; throws when persistence fails. */
    suspend fun write(settings: AppSettings)
}
