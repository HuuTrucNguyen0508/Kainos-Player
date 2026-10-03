package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.matching.TrackNormalizer
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.TrackSort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryCatalogTest {
    @Test
    fun localOnlyUsesLocalTracksAndMergedCatalogDedupesByCanonicalId() {
        val local = track("Local Title", "Ada", provider = ProviderId.LOCAL, canonicalId = "same")
        val saved = track("Saved Title", "Ada", provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "same")
        val spotify = track("Spotify Title", "Ada", provider = ProviderId.SPOTIFY, canonicalId = "spotify-only")

        val merged = libraryQueueSongs(
            local = listOf(local),
            saved = listOf(saved),
            spotify = listOf(spotify),
            sort = TrackSort.NAME_ASCENDING,
            localOnly = false,
            favoritesOnly = false,
            favorites = emptySet(),
        )
        assertEquals(listOf("same", "spotify-only"), merged.map { it.canonicalId })
        assertEquals("Local Title", merged.first { it.canonicalId == "same" }.title)
        assertEquals(1, libraryLocalCount(merged))
        assertEquals(1, librarySpotifyCount(merged))

        val localOnly = libraryQueueSongs(
            local = listOf(local),
            saved = listOf(saved),
            spotify = listOf(spotify),
            sort = TrackSort.NAME_ASCENDING,
            localOnly = true,
            favoritesOnly = false,
            favorites = emptySet(),
        )
        assertEquals(listOf("same"), localOnly.map { it.canonicalId })
    }

    @Test
    fun favoritesOnlyKeepsHeartedTracksAndSortIsApplied() {
        val amy = track("Amy", "Ada", provider = ProviderId.LOCAL, canonicalId = "amy")
        val zed = track("Zed", "Ada", provider = ProviderId.LOCAL, canonicalId = "zed")
        val sorted = libraryQueueSongs(
            local = listOf(zed, amy),
            saved = emptyList(),
            spotify = emptyList(),
            sort = TrackSort.NAME_ASCENDING,
            localOnly = true,
            favoritesOnly = false,
            favorites = emptySet(),
        )
        assertEquals(listOf("amy", "zed"), sorted.map { it.canonicalId })

        val hearts = libraryQueueSongs(
            local = listOf(zed, amy),
            saved = emptyList(),
            spotify = emptyList(),
            sort = TrackSort.NAME_DESCENDING,
            localOnly = true,
            favoritesOnly = true,
            favorites = setOf("amy"),
        )
        assertEquals(listOf("amy"), hearts.map { it.canonicalId })
    }

    @Test
    fun fuzzyDisplayRanksTitleHitsFirstWithoutChangingTheQueue() {
        val artistHit = track("Alpha", "Lemon", "Album", provider = ProviderId.LOCAL, canonicalId = "artist")
        val titleHit = track("Lemon", "Zed", "Album", provider = ProviderId.LOCAL, canonicalId = "title")
        val other = track("Bravo", "Qin", "Album", provider = ProviderId.LOCAL, canonicalId = "other")
        val queue = libraryQueueSongs(
            local = listOf(artistHit, titleHit, other),
            saved = emptyList(),
            spotify = emptyList(),
            sort = TrackSort.NAME_ASCENDING,
            localOnly = true,
            favoritesOnly = false,
            favorites = emptySet(),
        )
        val before = queue.map { it.canonicalId }
        val shown = libraryDisplaySongs(queue, TrackNormalizer.fold("lemon"))

        assertEquals(listOf("artist", "other", "title"), before)
        assertEquals(before, queue.map { it.canonicalId })
        assertEquals(listOf("title", "artist"), shown.map { it.canonicalId })
        assertEquals(queue, libraryDisplaySongs(queue, ""))
    }

    @Test
    fun equalFuzzyScoresKeepQueueOrder() {
        val first = track("Lemon", "Ada", "Peel", provider = ProviderId.LOCAL, canonicalId = "a")
        val second = track("Lemon", "Ada", "Peel", provider = ProviderId.LOCAL, canonicalId = "b")
        val queue = libraryQueueSongs(
            local = listOf(first, second),
            saved = emptyList(),
            spotify = emptyList(),
            sort = TrackSort.NAME_ASCENDING,
            localOnly = true,
            favoritesOnly = false,
            favorites = emptySet(),
        )
        val shown = libraryDisplaySongs(queue, TrackNormalizer.fold("lemon"))
        assertEquals(queue.map { it.canonicalId }, shown.map { it.canonicalId })
    }

    @Test
    fun albumsAndArtistsHonorFavoritesOnly() {
        val heart = track("Heart", "Ada", "Peel", provider = ProviderId.LOCAL, canonicalId = "heart")
        val other = track("Other", "Ada", "Peel", provider = ProviderId.LOCAL, canonicalId = "other")
        val solo = track("Solo", "Bea", "zeta", provider = ProviderId.LOCAL, canonicalId = "solo")
        val alpha = track("A", "Cara", "Alpha", provider = ProviderId.LOCAL, canonicalId = "alpha")

        val albums = libraryLocalAlbums(listOf(solo, alpha), favoritesOnly = false, favorites = emptySet())
        assertEquals(listOf("Alpha", "zeta"), albums.map { it.title })

        val heartedAlbums = libraryLocalAlbums(
            listOf(heart, other, solo),
            favoritesOnly = true,
            favorites = setOf("heart"),
        )
        assertEquals(listOf("Peel"), heartedAlbums.map { it.title })
        assertEquals(listOf("heart"), heartedAlbums.single().tracks.map { it.canonicalId })

        val artists = libraryLocalArtists(
            listOf(heart, other, solo),
            favoritesOnly = true,
            favorites = setOf("heart"),
        )
        assertEquals(listOf("Ada"), artists.map { it.artist.name })
        assertEquals(listOf("heart"), artists.single().tracks.map { it.canonicalId })
    }

    @Test
    fun playlistsHideWhenLocalOnlyOrFavoritesOnly() {
        val rows = listOf(
            PersistedKainosPlaylist(id = "kainos:playlist:road", title = "Road"),
            PersistedKainosPlaylist(id = "kainos:playlist:night", title = "Night"),
        )
        assertTrue(libraryFilteredKainosPlaylists(rows, needle = "", favoritesOnly = true).isEmpty())
        assertEquals(
            listOf("Night"),
            libraryFilteredKainosPlaylists(rows, TrackNormalizer.fold("night"), favoritesOnly = false).map { it.title },
        )
        assertEquals(
            listOf("Road", "Night"),
            libraryFilteredKainosPlaylists(rows, needle = "", favoritesOnly = false).map { it.title },
        )

        val chill = Playlist(
            canonicalId = "chill",
            title = "Chill",
            source = ProviderEntityRef(ProviderId.SPOTIFY, "chill"),
        )
        val weekly = Playlist(
            canonicalId = "weekly",
            title = "Discover Weekly",
            source = ProviderEntityRef(ProviderId.SPOTIFY, "weekly"),
        )
        val catalog = listOf(chill, weekly)
        assertTrue(
            libraryFilteredSpotifyPlaylists(catalog, needle = "", localOnly = true, favoritesOnly = false).isEmpty(),
        )
        assertTrue(
            libraryFilteredSpotifyPlaylists(catalog, needle = "", localOnly = false, favoritesOnly = true).isEmpty(),
        )
        assertEquals(
            listOf("Discover Weekly", "Chill"),
            libraryFilteredSpotifyPlaylists(catalog, needle = "", localOnly = false, favoritesOnly = false).map { it.title },
        )
    }
}
