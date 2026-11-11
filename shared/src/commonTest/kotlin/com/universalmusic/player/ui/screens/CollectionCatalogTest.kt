package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.spotify.SpotifyRefusedException
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.AlbumRef
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CollectionCatalogTest {
    private fun TestScope.open(catalog: CollectionCatalog, ref: CollectionRef): CollectionPresenter {
        val presenter = catalog.open(ref, backgroundScope)
        runCurrent()
        return presenter
    }

    private fun TestScope.catalog(
        local: List<Track> = emptyList(),
        streamed: List<Track> = emptyList(),
        localOnly: MutableStateFlow<Boolean> = MutableStateFlow(false),
        topTracks: suspend (String) -> List<Track> = { error("unused") },
        albums: suspend (String) -> List<Album> = { error("unused") },
    ) = testCollectionCatalog(
        StandardTestDispatcher(testScheduler),
        localTracks = MutableStateFlow(local),
        streamedTracks = MutableStateFlow(streamed),
        localOnly = localOnly,
        spotifyArtistTopTracks = topTracks,
        spotifyArtistAlbums = albums,
    )

    @Test
    fun anOpenAlbumFollowsItsTracksWhenTheTagPassRekeysIt() = runTest {
        val original = track("Song", "Artist", album = "Folder", provider = ProviderId.LOCAL, canonicalId = "local:1")
        val local = MutableStateFlow(listOf(original))
        val aliases = MutableStateFlow<Map<String, List<AlbumRef>>>(emptyMap())
        val page = open(testCollectionCatalog(StandardTestDispatcher(testScheduler), localTracks = local, albumAliases = aliases),
            CollectionRef.LocalAlbum(original.album!!.canonicalId, "Folder"))
        val tagged = original.copy(album = AlbumRef("local-album:tagged", "Tagged album"))
        aliases.value = mapOf(original.album!!.canonicalId to listOf(tagged.album!!))
        local.value = listOf(tagged)
        runCurrent()
        assertEquals(listOf(tagged), page.state.value.tracks)
        assertEquals("Tagged album", page.state.value.header.title)
    }

    @Test
    fun aSpotifyArtistThatRefusesTopSongsStillShowsItsAlbumsWithTheReason() = runTest {
        val album = Album("spotify-album:a", "Low Tide", listOf(ArtistRef("spotify-artist:x", "Tide")), year = 2018)
        val page = open(
            catalog(
                topTracks = { throw SpotifyRefusedException("artist top songs", 403) },
                albums = { listOf(album) },
            ),
            CollectionRef.SpotifyArtist("x", "Tide"),
        )
        val state = page.state.value
        assertNull(state.error)
        assertEquals(listOf("Low Tide"), state.albums.map { it.title })
        assertEquals(emptyList(), state.tracks)
        assertEquals("Spotify does not share this artist's top songs with Kainos.", state.notice)
    }

    @Test
    fun aSpotifyArtistThatRefusesEverythingFailsWithTheReasonAndNoGuesses() = runTest {
        val page = open(
            catalog(
                topTracks = { throw SpotifyRefusedException("artist top songs", 403) },
                albums = { throw SpotifyRefusedException("artist albums", 403) },
            ),
            CollectionRef.SpotifyArtist("x", "Tide"),
        )
        assertEquals("Spotify does not share artist top songs with this app (HTTP 403).", page.state.value.error)
        assertTrue(page.state.value.tracks.isEmpty())
    }

    @Test
    fun aLibraryArtistIncludesHeartedStreamsUnlessLocalFilesOnly() = runTest {
        val local = track("Harbour", "Neon Coast", album = "Quay", provider = ProviderId.LOCAL, canonicalId = "local:1")
        val hearted = track("Static", "neon coast", provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "yt:1")
        val other = track("Elsewhere", "Tide", provider = ProviderId.SPOTIFY, canonicalId = "spotify:1")
        val localOnly = MutableStateFlow(false)
        val page = open(catalog(local = listOf(local), streamed = listOf(hearted, other), localOnly = localOnly), CollectionRef.LibraryArtist("Neon Coast"))
        assertEquals(setOf("Harbour", "Static"), page.state.value.tracks.map { it.title }.toSet())
        assertEquals(listOf("Quay"), page.state.value.albums.map { it.title })

        localOnly.value = true
        runCurrent()
        assertEquals(listOf("Harbour"), page.state.value.tracks.map { it.title })
    }

    @Test
    fun goToAlbumAndArtistPickTheSongsOwnSource() {
        val spotify = Track(
            canonicalId = "spotify:s",
            title = "Song",
            artists = listOf(ArtistRef("spotify-artist:a1", "One"), ArtistRef("spotify-artist:a2", "Two")),
            album = AlbumRef("spotify-album:al", "Record"),
        )
        assertEquals(CollectionRef.SpotifyAlbum("al", "Record"), spotify.albumPage())
        assertEquals(listOf("spotify-artist:a1", "spotify-artist:a2"), spotify.artistPages().map { it.key })

        val local = track("Song", "Mara", album = "Quay", provider = ProviderId.LOCAL)
        assertEquals(CollectionRef.LocalAlbum("album-Quay", "Quay"), local.albumPage())
        assertEquals(listOf(CollectionRef.LibraryArtist("Mara")), local.artistPages())

        // A YouTube upload has no album page.
        assertNull(track("Clip", "Uploader", provider = ProviderId.YOUTUBE_MUSIC).albumPage())
    }

    @Test
    fun startRadioIsOfferedOnlyForSourcesWithARadio() {
        val actions = TrackActionsPresenter(
            favorites = MutableStateFlow(emptySet()),
            playlists = MutableStateFlow(emptyList<PersistedKainosPlaylist>()),
            playNextAction = {},
            addToQueueAction = {},
            toggleFavoriteTrack = { false },
            syncNowPlayingFavorite = { _, _ -> },
            addTracksToPlaylist = { _, _ -> null },
            createPlaylistWith = { _, _ -> error("unused") },
            startRadioAction = {},
        )
        assertTrue(actions.canStartRadio(track("A", "B", provider = ProviderId.SPOTIFY)))
        assertTrue(actions.canStartRadio(track("A", "B", provider = ProviderId.YOUTUBE_MUSIC)))
        assertFalse(actions.canStartRadio(track("A", "B", provider = ProviderId.LOCAL)))
    }
}
