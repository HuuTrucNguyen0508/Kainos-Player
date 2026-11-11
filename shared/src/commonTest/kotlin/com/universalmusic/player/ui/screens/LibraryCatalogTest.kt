package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.cache.offlineOnly
import com.universalmusic.player.data.cache.offlineQueue
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.matching.TrackNormalizer
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.AlbumRef
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Track
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
    fun localAlbumsListTracksInDiscAndTrackOrderUnderTheAlbumArtist() {
        val album = AlbumRef("local-album:ado|kyogen|1", "Kyogen", year = 2022, artists = listOf(ArtistRef("local-artist:ado", "Ado")))
        fun song(title: String, artist: String, disc: Int?, number: Int?) =
            track(title, artist, "Kyogen", provider = ProviderId.LOCAL, canonicalId = "local:$title")
                .copy(album = album, discNumber = disc, trackNumber = number)
        val scanOrder = listOf(
            song("Outro", "Ado", 2, 1),
            song("Odo", "Ado feat. Giga", 1, 2),
            song("Bonus", "Ado", null, null),
            song("Usseewa", "Ado", 1, 1),
        )

        val kyogen = libraryLocalAlbums(scanOrder, favoritesOnly = false, favorites = emptySet()).single()

        assertEquals(listOf("Usseewa", "Odo", "Outro", "Bonus"), kyogen.tracks.map { it.title })
        assertEquals(listOf("Ado"), kyogen.artists.map { it.name })
        assertEquals(2022, kyogen.year)
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

    @Test
    fun readyOfflineKeepsLocalAndCachedTracksWholeAndPlaysOnlyTheirDiskCopies() {
        val file = PlaybackSource(ProviderId.LOCAL, "/m/a.flac", isPlayable = true, handle = PlaybackHandle.Url("file:///m/a.flac"))
        val cache = PlaybackSource(ProviderId.LOCAL, "hearted-yt:v1", isPlayable = true, handle = PlaybackHandle.Url("file:///c/v1.m4a"))
        val local = Track("local:a", "Alpha", emptyList(), sources = listOf(file))
        val mirror = Track("spotify:s1", "Mirror", emptyList(), sources = listOf(
            PlaybackSource(ProviderId.SPOTIFY, "s1", isPlayable = true, handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, "s1")),
        ))
        val stream = track("Stream", "Ada", provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "yt:v9")
        // Stands in for the hearted-audio cache attaching the downloaded file before filtering.
        val attachCache = { t: Track -> if (t.canonicalId == "spotify:s1") t.copy(sources = listOf(cache) + t.sources) else t }

        val queue = libraryQueueSongs(
            local = listOf(local),
            saved = listOf(stream),
            spotify = listOf(mirror),
            sort = TrackSort.NAME_ASCENDING,
            localOnly = false,
            favoritesOnly = false,
            favorites = emptySet(),
            readyOffline = true,
            offlineTrack = { attachCache(it).offlineOnly() },
        )

        assertEquals(listOf("local:a", "spotify:s1"), queue.map { it.canonicalId })
        // Rows keep their streams so history, hearts and playlists never save a disk-only copy.
        assertEquals(listOf(local, mirror), queue)
        val offline = offlineQueue(queue) { attachCache(it).offlineOnly() }
        assertEquals(listOf(listOf(file), listOf(cache)), offline.map { it.sources })
        assertEquals(1, libraryLocalCount(offline))
        assertEquals(1, libraryCachedCount(offline))
    }
}
