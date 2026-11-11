package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.cache.offlineOnly
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.TrackSort

internal data class LibraryUiState(
    val songSort: TrackSort,
    val localOnly: Boolean,
    val favoritesOnly: Boolean,
    val readyOffline: Boolean,
    val favorites: Set<String>,
    val query: String,
    val needle: String,
    val queueSongs: List<Track>,
    val songs: List<Track>,
    val localTracks: List<Track>,
    val albums: List<Album>,
    val artists: List<LibraryArtistEntry>,
    /** Configured music folders as trees; Favorites only keeps hearted files, the other chips change nothing. */
    val folders: List<LibraryFolder>,
    val kainosRows: List<PersistedKainosPlaylist>,
    val kainosPlaylists: List<Playlist>,
    val spotifyPlaylistCatalog: List<Playlist>,
    val spotifyPlaylists: List<Playlist>,
    val pinnedKeys: Set<String>,
    val localCount: Int,
    val cachedCount: Int,
    val spotifyCount: Int,
    val localState: ProviderState,
    val spotifyConnected: Boolean,
    val spotifyLoading: Boolean,
    val spotifyError: String?,
    /** When Spotify last confirmed the saved Spotify songs shown, while a refresh could not; else null. */
    val spotifySavedAtMs: Long? = null,
    val downloads: Map<String, DownloadItemState>,
    val searchIndex: LibrarySearchIndex = LibrarySearchIndex(queueSongs, albums, artists),
)

internal fun LibraryUiState.withQuery(query: String): LibraryUiState {
    if (this.query == query) return this
    val needle = libraryNeedle(query)
    return copy(
        query = query,
        needle = needle,
        songs = searchIndex.songs(needle),
        albums = searchIndex.albums(needle),
        artists = searchIndex.artists(needle),
        kainosPlaylists = libraryFilteredKainosPlaylists(kainosRows, needle, favoritesOnly),
        spotifyPlaylists = libraryFilteredSpotifyPlaylists(
            spotifyPlaylistCatalog,
            needle,
            localOnly || readyOffline,
            favoritesOnly,
        ),
    )
}

internal fun deriveLibraryUiState(
    settings: AppSettings,
    saved: List<Track>,
    favorites: Set<String>,
    localTracks: List<Track>,
    localState: ProviderState,
    spotifyTracks: List<Track>,
    spotifyPlaylists: List<Playlist>,
    kainosRows: List<PersistedKainosPlaylist>,
    homePins: List<PersistedHomePin>,
    spotifyLoading: Boolean,
    spotifyError: String?,
    spotifySavedAtMs: Long? = null,
    spotifyState: ProviderState,
    downloads: Map<String, DownloadItemState>,
    query: String,
    musicFolders: List<String> = emptyList(),
    offlineTrack: (Track) -> Track? = Track::offlineOnly,
): LibraryUiState {
    val needle = ""
    val localOnly = settings.libraryLocalOnly
    val favoritesOnly = settings.libraryFavoritesOnly
    val readyOffline = settings.libraryReadyOffline
    val duplicates = libraryDuplicates(settings.libraryHideDuplicates, localTracks, saved, spotifyTracks)
    // A heart on a hidden copy shows on, and filters in, the copy that is listed.
    val shownFavorites = duplicates.effectiveFavorites(favorites)
    val queueSongs = libraryQueueSongs(
        local = localTracks,
        saved = saved,
        spotify = spotifyTracks,
        sort = settings.librarySongSort,
        localOnly = localOnly,
        favoritesOnly = favoritesOnly,
        favorites = shownFavorites,
        readyOffline = readyOffline,
        offlineTrack = offlineTrack,
        duplicates = duplicates,
    )
    return LibraryUiState(
        songSort = settings.librarySongSort,
        localOnly = localOnly,
        favoritesOnly = favoritesOnly,
        readyOffline = readyOffline,
        favorites = shownFavorites,
        query = "",
        needle = needle,
        queueSongs = queueSongs,
        songs = libraryDisplaySongs(queueSongs, needle),
        localTracks = localTracks,
        albums = libraryFilteredAlbums(libraryLocalAlbums(localTracks, favoritesOnly, shownFavorites), needle),
        artists = libraryFilteredArtists(libraryLocalArtists(localTracks, favoritesOnly, shownFavorites), needle),
        folders = libraryFolderTree(
            musicFolders,
            if (favoritesOnly) localTracks.filter { it.canonicalId in shownFavorites } else localTracks,
        ),
        kainosRows = kainosRows,
        kainosPlaylists = libraryFilteredKainosPlaylists(kainosRows, needle, favoritesOnly),
        spotifyPlaylistCatalog = spotifyPlaylists,
        spotifyPlaylists = libraryFilteredSpotifyPlaylists(spotifyPlaylists, needle, localOnly || readyOffline, favoritesOnly),
        pinnedKeys = libraryPinnedKeys(homePins),
        localCount = libraryLocalCount(queueSongs),
        cachedCount = if (readyOffline) libraryCachedCount(queueSongs.mapNotNull(offlineTrack)) else 0,
        spotifyCount = librarySpotifyCount(queueSongs),
        localState = localState,
        // A saved library shown offline still gets its tally, with its age.
        spotifyConnected = spotifyState == ProviderState.AVAILABLE || spotifyState == ProviderState.RATE_LIMITED ||
            spotifySavedAtMs != null,
        spotifyLoading = spotifyLoading,
        spotifyError = spotifyError,
        spotifySavedAtMs = spotifySavedAtMs,
        downloads = downloads,
    ).withQuery(query)
}
