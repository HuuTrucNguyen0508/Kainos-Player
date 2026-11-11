package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.PlaylistTracksPage
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A catalog with nothing in it unless a test hands it songs, playlists or provider answers. */
internal fun testCollectionCatalog(
    dispatcher: CoroutineDispatcher,
    localTracks: StateFlow<List<Track>> = MutableStateFlow(emptyList()),
    streamedTracks: StateFlow<List<Track>> = MutableStateFlow(emptyList()),
    albumAliases: StateFlow<Map<String, List<com.universalmusic.player.domain.model.AlbumRef>>> = MutableStateFlow(emptyMap()),
    localOnly: StateFlow<Boolean> = MutableStateFlow(false),
    playlists: StateFlow<List<PersistedKainosPlaylist>> = MutableStateFlow(emptyList()),
    playlistEditor: (String) -> PlaylistEditor? = { null },
    spotifyAlbum: suspend (String) -> Album = { error("no Spotify album in this test") },
    spotifyArtistTopTracks: suspend (String) -> List<Track> = { error("no Spotify artist in this test") },
    spotifyArtistAlbums: suspend (String) -> List<Album> = { error("no Spotify artist in this test") },
    providerPlaylistPage: suspend (ProviderEntityRef, String?) -> PlaylistTracksPage = { _, _ -> error("no playlist in this test") },
) = CollectionCatalog(
    localTracks = localTracks,
    albumAliases = albumAliases,
    streamedTracks = streamedTracks,
    localOnly = localOnly,
    playlists = playlists,
    playlistEditor = playlistEditor,
    spotifyAlbum = spotifyAlbum,
    spotifyArtistTopTracks = spotifyArtistTopTracks,
    spotifyArtistAlbums = spotifyArtistAlbums,
    providerPlaylistPage = providerPlaylistPage,
    dispatcher = dispatcher,
    ioDispatcher = dispatcher,
)

internal fun testCollectionStack(scope: CoroutineScope, catalog: CollectionCatalog): CollectionStack {
    var id = 0L
    return CollectionStack(scope, catalog) { ++id }
}
