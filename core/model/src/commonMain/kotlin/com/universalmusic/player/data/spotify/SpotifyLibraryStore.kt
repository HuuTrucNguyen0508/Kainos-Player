package com.universalmusic.player.data.spotify

import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.Track

/**
 * One Liked Songs entry. [spotifyTrackId] and [addedAt] identify it across refreshes: Spotify lists
 * Liked Songs newest first, and liking a song again gives it a new [addedAt].
 */
data class SavedLikedTrack(
    val spotifyTrackId: String,
    val addedAt: String?,
    val track: Track,
    /**
     * How many entries Spotify could not show sit directly above this one (after the previous shown
     * entry), so a refresh knows how many of them were above the newest song it already had.
     */
    val unshowableBefore: Int = 0,
)

/** Liked Songs as of the last check, with Spotify's own count of entries at that time. */
data class SavedLikedSongs(
    val tracks: List<SavedLikedTrack>,
    /** Spotify's total at the last check, counting entries it cannot show. */
    val total: Int?,
    /** How many of [total] Spotify could not show (removed or unavailable songs). */
    val unmapped: Int,
    val checkedAtMs: Long?,
)

/**
 * A playlist of the user's library, without its songs. [tracksSnapshotId] is the [snapshotId] the
 * stored songs were read at (empty when Spotify gave none); null when no songs are stored.
 */
data class SavedSpotifyPlaylist(
    val playlist: Playlist,
    val snapshotId: String?,
    val tracksSnapshotId: String? = null,
) {
    val id: String get() = playlist.source.providerEntityId

    /** The stored songs match the playlist as Spotify last described it. */
    val tracksCurrent: Boolean get() = tracksSnapshotId != null && tracksSnapshotId == snapshotId.orEmpty()
}

data class SavedSpotifyLibrary(
    val accountId: String,
    val liked: SavedLikedSongs,
    val playlists: List<SavedSpotifyPlaylist>,
    val playlistsCheckedAtMs: Long?,
)

/** The signed-in account's Spotify library on this device. Rows of one account only. */
interface SpotifyLibraryStore {
    /** The account whose library is stored, or null when nothing is. */
    suspend fun storedAccount(): String?

    suspend fun load(accountId: String): SavedSpotifyLibrary?

    suspend fun saveLiked(accountId: String, liked: SavedLikedSongs)

    /** Replaces the playlist list; songs of playlists no longer listed are deleted. */
    suspend fun savePlaylists(accountId: String, playlists: List<SavedSpotifyPlaylist>, checkedAtMs: Long)

    /** Stored songs of [playlistId], or null when none are stored. */
    suspend fun playlistTracks(accountId: String, playlistId: String): List<Track>?

    suspend fun savePlaylistTracks(accountId: String, playlistId: String, snapshotId: String?, tracks: List<Track>, savedAtMs: Long)

    /** Deletes every account's library. */
    suspend fun clear()
}
