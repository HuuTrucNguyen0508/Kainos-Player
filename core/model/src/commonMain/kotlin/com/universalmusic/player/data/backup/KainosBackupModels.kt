package com.universalmusic.player.data.backup

import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.FolioTint
import com.universalmusic.player.data.settings.SkinChoice
import com.universalmusic.player.data.settings.ThemeMode
import com.universalmusic.player.domain.model.SourceSelectionMode
import com.universalmusic.player.domain.model.TrackSort
import kotlinx.serialization.Serializable

const val KAINOS_BACKUP_FORMAT = "kainos-backup"
const val KAINOS_BACKUP_VERSION = 1

/**
 * One user-chosen backup file, format `kainos-backup` version 1.
 *
 * It holds only portable identities: hearts as `yt:` / `spotify:` / `localkey:lc1:` / `localfile:` ids,
 * Kainos playlists in their Home sync form, and Kainos or Spotify playlist pins. It never holds
 * sign-ins, API keys or client ids, pairing keys or certificates, stream URLs, SAF grants,
 * device `local:` ids or any absolute path; album and folder pins stay on the device for that reason.
 *
 * Restore merges and never removes anything:
 * - Hearts: union. A heart not on this device is added with a new revision, even over an older unheart.
 *   Spotify hearts and Spotify playlist pins apply only when this device is signed in to [spotifyAccountId].
 * - Playlists, by id: a missing one is added; when both are live the higher revision wins; one deleted
 *   here comes back with a new revision.
 * - Pins: those whose kind and target are not pinned yet are appended after the current pins.
 * - Preferences: each value in the file replaces the current one.
 * Restoring the same file again changes nothing.
 */
@Serializable
data class KainosBackupFile(
    val format: String = KAINOS_BACKUP_FORMAT,
    val version: Int = KAINOS_BACKUP_VERSION,
    val createdAtMs: Long = 0,
    /** The Spotify account that owns the Spotify hearts and pins in this file. */
    val spotifyAccountId: String? = null,
    /** Hearted tracks, display metadata included, with a portable [PersistedTrack.canonicalId]. */
    val hearts: List<PersistedTrack> = emptyList(),
    val playlists: List<PersistedKainosPlaylist> = emptyList(),
    val homePins: List<PersistedHomePin> = emptyList(),
    val preferences: BackupPreferences = BackupPreferences(),
)

/** Settings that are neither secret nor tied to this device. A null value leaves the setting alone. */
@Serializable
data class BackupPreferences(
    val themeMode: ThemeMode? = null,
    val skin: SkinChoice? = null,
    val tint: FolioTint? = null,
    val followCaelestiaScheme: Boolean? = null,
    val compactMode: Boolean? = null,
    val sourceSelection: SourceSelectionMode? = null,
    val gapless: Boolean? = null,
    val volumeLevelling: Boolean? = null,
    val librarySongSort: TrackSort? = null,
    val libraryLocalOnly: Boolean? = null,
    val libraryFavoritesOnly: Boolean? = null,
    val libraryReadyOffline: Boolean? = null,
    val searchRadioEnabled: Boolean? = null,
)

fun AppSettings.backupPreferences(): BackupPreferences = BackupPreferences(
    themeMode = themeMode,
    skin = skin,
    tint = tint,
    followCaelestiaScheme = followCaelestiaScheme,
    compactMode = compactMode,
    sourceSelection = sourceSelection,
    gapless = gapless,
    volumeLevelling = volumeLevelling,
    librarySongSort = librarySongSort,
    libraryLocalOnly = libraryLocalOnly,
    libraryFavoritesOnly = libraryFavoritesOnly,
    libraryReadyOffline = libraryReadyOffline,
    searchRadioEnabled = searchRadioEnabled,
)

fun AppSettings.withBackupPreferences(preferences: BackupPreferences): AppSettings = copy(
    themeMode = preferences.themeMode ?: themeMode,
    skin = preferences.skin ?: skin,
    tint = preferences.tint ?: tint,
    followCaelestiaScheme = preferences.followCaelestiaScheme ?: followCaelestiaScheme,
    compactMode = preferences.compactMode ?: compactMode,
    sourceSelection = preferences.sourceSelection ?: sourceSelection,
    gapless = preferences.gapless ?: gapless,
    volumeLevelling = preferences.volumeLevelling ?: volumeLevelling,
    librarySongSort = preferences.librarySongSort ?: librarySongSort,
    libraryLocalOnly = preferences.libraryLocalOnly ?: libraryLocalOnly,
    libraryFavoritesOnly = preferences.libraryFavoritesOnly ?: libraryFavoritesOnly,
    libraryReadyOffline = preferences.libraryReadyOffline ?: libraryReadyOffline,
    searchRadioEnabled = preferences.searchRadioEnabled ?: searchRadioEnabled,
)

/** How many preference values [preferences] would change. */
fun AppSettings.backupPreferenceChanges(preferences: BackupPreferences): Int {
    val current = backupPreferences()
    val next = withBackupPreferences(preferences).backupPreferences()
    return listOf(
        current.themeMode != next.themeMode,
        current.skin != next.skin,
        current.tint != next.tint,
        current.followCaelestiaScheme != next.followCaelestiaScheme,
        current.compactMode != next.compactMode,
        current.sourceSelection != next.sourceSelection,
        current.gapless != next.gapless,
        current.volumeLevelling != next.volumeLevelling,
        current.librarySongSort != next.librarySongSort,
        current.libraryLocalOnly != next.libraryLocalOnly,
        current.libraryFavoritesOnly != next.libraryFavoritesOnly,
        current.libraryReadyOffline != next.libraryReadyOffline,
        current.searchRadioEnabled != next.searchRadioEnabled,
    ).count { it }
}

/** Spotify-scoped pins follow the same account rule as Spotify hearts. */
fun PersistedHomePin.isSpotifyScoped(): Boolean =
    kind == HomePinKind.PROVIDER_PLAYLIST && (provider == "SPOTIFY" || targetId.startsWith("spotify"))

/** What a restore of one file did, or would do. */
data class BackupRestoreCounts(
    val heartsAdded: Int = 0,
    val heartsAlready: Int = 0,
    val playlistsAdded: Int = 0,
    val playlistsUpdated: Int = 0,
    val playlistsRestored: Int = 0,
    val playlistsKept: Int = 0,
    val pinsAdded: Int = 0,
    val pinsAlready: Int = 0,
    val preferencesChanged: Int = 0,
    /** Spotify hearts and pins left out because this device is on another Spotify account or none. */
    val spotifySkipped: Int = 0,
) {
    val changes: Int
        get() = heartsAdded + playlistsAdded + playlistsUpdated + playlistsRestored + pinsAdded + preferencesChanged
}
