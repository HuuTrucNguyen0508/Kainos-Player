package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.cache.DownloadItemState
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
    val favorites: Set<String>,
    val query: String,
    val needle: String,
    val queueSongs: List<Track>,
    val songs: List<Track>,
    val localTracks: List<Track>,
    val albums: List<Album>,
    val artists: List<LibraryArtistEntry>,
    val kainosRows: List<PersistedKainosPlaylist>,
    val kainosPlaylists: List<Playlist>,
    val spotifyPlaylistCatalog: List<Playlist>,
    val spotifyPlaylists: List<Playlist>,
    val pinnedKeys: Set<String>,
    val localCount: Int,
    val spotifyCount: Int,
    val localState: ProviderState,
    val spotifyConnected: Boolean,
    val spotifyLoading: Boolean,
    val spotifyError: String?,
    val downloads: Map<String, DownloadItemState>,
)

internal fun LibraryUiState.withQuery(query: String): LibraryUiState {
    if (this.query == query) return this
    val needle = libraryNeedle(query)
    return copy(
        query = query,
        needle = needle,
        songs = libraryDisplaySongs(queueSongs, needle),
        albums = libraryFilteredAlbums(libraryLocalAlbums(localTracks, favoritesOnly, favorites), needle),
        artists = libraryFilteredArtists(libraryLocalArtists(localTracks, favoritesOnly, favorites), needle),
        kainosPlaylists = libraryFilteredKainosPlaylists(kainosRows, needle, favoritesOnly),
        spotifyPlaylists = libraryFilteredSpotifyPlaylists(
            spotifyPlaylistCatalog,
            needle,
            localOnly,
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
    spotifyState: ProviderState,
    downloads: Map<String, DownloadItemState>,
    query: String,
): LibraryUiState {
    val needle = libraryNeedle(query)
    val localOnly = settings.libraryLocalOnly
    val favoritesOnly = settings.libraryFavoritesOnly
    val queueSongs = libraryQueueSongs(
        local = localTracks,
        saved = saved,
        spotify = spotifyTracks,
        sort = settings.librarySongSort,
        localOnly = localOnly,
        favoritesOnly = favoritesOnly,
        favorites = favorites,
    )
    return LibraryUiState(
        songSort = settings.librarySongSort,
        localOnly = localOnly,
        favoritesOnly = favoritesOnly,
        favorites = favorites,
        query = query,
        needle = needle,
        queueSongs = queueSongs,
        songs = libraryDisplaySongs(queueSongs, needle),
        localTracks = localTracks,
        albums = libraryFilteredAlbums(libraryLocalAlbums(localTracks, favoritesOnly, favorites), needle),
        artists = libraryFilteredArtists(libraryLocalArtists(localTracks, favoritesOnly, favorites), needle),
        kainosRows = kainosRows,
        kainosPlaylists = libraryFilteredKainosPlaylists(kainosRows, needle, favoritesOnly),
        spotifyPlaylistCatalog = spotifyPlaylists,
        spotifyPlaylists = libraryFilteredSpotifyPlaylists(spotifyPlaylists, needle, localOnly, favoritesOnly),
        pinnedKeys = libraryPinnedKeys(homePins),
        localCount = libraryLocalCount(queueSongs),
        spotifyCount = librarySpotifyCount(queueSongs),
        localState = localState,
        spotifyConnected = spotifyState == ProviderState.AVAILABLE || spotifyState == ProviderState.RATE_LIMITED,
        spotifyLoading = spotifyLoading,
        spotifyError = spotifyError,
        downloads = downloads,
    )
}
