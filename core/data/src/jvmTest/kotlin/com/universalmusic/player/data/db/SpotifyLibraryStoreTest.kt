package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.spotify.SavedLikedSongs
import com.universalmusic.player.data.spotify.SavedLikedTrack
import com.universalmusic.player.data.spotify.SavedSpotifyPlaylist
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SpotifyLibraryStoreTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { KainosDatabase.Schema.create(it) }
    private fun store() = DbSpotifyLibraryStore(KainosStorage(openDriver = { driver }, clock = { 0 }))

    private fun song(id: String) = Track(
        canonicalId = "spotify:track:$id",
        title = "Song $id",
        artists = listOf(ArtistRef("spotify:artist:a", "Artist")),
        durationMs = 180_000,
        sources = listOf(PlaybackSource(ProviderId.SPOTIFY, id, isPlayable = true,
            handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, id, 180_000))),
    )

    private fun playlist(id: String, title: String = "List $id") = Playlist(
        canonicalId = "spotify-playlist:$id",
        title = title,
        description = "About $id",
        artwork = Artwork("https://i.scdn.co/$id"),
        ownerName = "Me",
        trackCount = 2,
        source = ProviderEntityRef(ProviderId.SPOTIFY, id),
    )

    @Test
    fun likedSongsAndPlaylistsSurviveARestartInOrder() = runTest {
        store().saveLiked("me", SavedLikedSongs(
            tracks = listOf(SavedLikedTrack("b", "2026-11-10T00:00:00Z", song("b")), SavedLikedTrack("a", null, song("a"))),
            total = 3,
            unmapped = 1,
            checkedAtMs = 500,
        ))
        store().savePlaylists("me", listOf(SavedSpotifyPlaylist(playlist("p2"), "s2"), SavedSpotifyPlaylist(playlist("p1"), "s1")), 600)
        store().savePlaylistTracks("me", "p1", "s1", listOf(song("x"), song("y")), 700)

        val reopened = store()
        val saved = reopened.load("me")!!

        assertEquals("me", reopened.storedAccount())
        assertEquals(listOf("b", "a"), saved.liked.tracks.map { it.spotifyTrackId })
        assertEquals("2026-11-10T00:00:00Z", saved.liked.tracks.first().addedAt)
        assertEquals("Song b", saved.liked.tracks.first().track.title)
        assertEquals(3 to 1, saved.liked.total to saved.liked.unmapped)
        assertEquals(500L, saved.liked.checkedAtMs)
        assertEquals(listOf("p2", "p1"), saved.playlists.map { it.id })
        assertEquals(playlist("p1"), saved.playlists[1].playlist)
        assertEquals(listOf(false, true), saved.playlists.map { it.tracksCurrent })
        assertEquals(600L, saved.playlistsCheckedAtMs)
        assertEquals(listOf("Song x", "Song y"), reopened.playlistTracks("me", "p1")!!.map { it.title })
        assertNull(reopened.playlistTracks("me", "p2"))
    }

    @Test
    fun savedSongsKeepAlbumOrderYearAndPlayability() = runTest {
        val unplayable = song("u").let { track ->
            track.copy(
                trackNumber = 7,
                discNumber = 2,
                year = 1999,
                sources = track.sources.map { it.copy(isPlayable = false) },
            )
        }
        store().saveLiked("me", SavedLikedSongs(listOf(SavedLikedTrack("u", null, unplayable)), 1, 0, 1))
        store().savePlaylists("me", listOf(SavedSpotifyPlaylist(playlist("p"), "s")), 1)
        store().savePlaylistTracks("me", "p", "s", listOf(unplayable), 1)

        val reopened = store()
        assertEquals(unplayable, reopened.load("me")!!.liked.tracks.single().track)
        assertEquals(listOf(unplayable), reopened.playlistTracks("me", "p"))
    }

    @Test
    fun aChangedSnapshotKeepsTheOldSongsButNotCurrent() = runTest {
        val store = store()
        store.savePlaylists("me", listOf(SavedSpotifyPlaylist(playlist("p"), "s1")), 1)
        store.savePlaylistTracks("me", "p", "s1", listOf(song("x")), 2)
        val stored = store.load("me")!!.playlists.single()

        store.savePlaylists("me", listOf(SavedSpotifyPlaylist(playlist("p"), "s2", stored.tracksSnapshotId)), 3)

        val changed = store.load("me")!!.playlists.single()
        assertEquals("s1", changed.tracksSnapshotId)
        assertEquals(false, changed.tracksCurrent)
        assertEquals(1, store.playlistTracks("me", "p")!!.size)
    }

    @Test
    fun aPlaylistThatLeftTheLibraryLosesItsSongs() = runTest {
        val store = store()
        store.savePlaylists("me", listOf(SavedSpotifyPlaylist(playlist("keep"), "k"), SavedSpotifyPlaylist(playlist("gone"), "g")), 1)
        store.savePlaylistTracks("me", "keep", "k", listOf(song("1")), 2)
        store.savePlaylistTracks("me", "gone", "g", listOf(song("2")), 2)

        store.savePlaylists("me", listOf(SavedSpotifyPlaylist(playlist("keep"), "k", "k")), 3)
        store.savePlaylistTracks("me", "gone", "g", listOf(song("3")), 4)

        assertEquals(listOf("keep"), store.load("me")!!.playlists.map { it.id })
        assertNull(store.playlistTracks("me", "gone"))
        val rows = driver.executeQuery(null, "SELECT count(*) FROM spotify_playlist_track", { c ->
            c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0))
        }, 0).value
        assertEquals(1L, rows)
    }

    @Test
    fun anotherAccountsWriteReplacesTheStoredLibrary() = runTest {
        val store = store()
        store.saveLiked("old", SavedLikedSongs(listOf(SavedLikedTrack("a", null, song("a"))), 1, 0, 1))
        store.savePlaylists("old", listOf(SavedSpotifyPlaylist(playlist("p"), "s")), 1)
        store.savePlaylistTracks("old", "p", "s", listOf(song("x")), 1)

        store.saveLiked("new", SavedLikedSongs(listOf(SavedLikedTrack("b", null, song("b"))), 1, 0, 2))

        assertNull(store.load("old"))
        assertNull(store.playlistTracks("old", "p"))
        assertEquals("new", store.storedAccount())
        assertEquals(listOf("b"), store.load("new")!!.liked.tracks.map { it.spotifyTrackId })
    }

    @Test
    fun clearForgetsEverything() = runTest {
        val store = store()
        store.saveLiked("me", SavedLikedSongs(listOf(SavedLikedTrack("a", null, song("a"))), 1, 0, 1))
        store.savePlaylists("me", listOf(SavedSpotifyPlaylist(playlist("p"), "s")), 1)
        store.savePlaylistTracks("me", "p", "s", listOf(song("x")), 1)

        store.clear()

        assertNull(store.storedAccount())
        assertNull(store.load("me"))
    }
}
