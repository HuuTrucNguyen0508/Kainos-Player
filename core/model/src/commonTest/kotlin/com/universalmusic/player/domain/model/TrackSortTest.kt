package com.universalmusic.player.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackSortTest {
    @Test
    fun namesAreSortedCaseInsensitivelyAfterTrimming() {
        val tracks = listOf(track(" zulu ", "z"), track("alpha", "a"), track("Bravo", "b"))

        val sorted = TrackSort.NAME_ASCENDING.sort(tracks)

        assertEquals(listOf("alpha", "Bravo", " zulu "), sorted.map { it.title })
        assertEquals(listOf(" zulu ", "Bravo", "alpha"), TrackSort.NAME_DESCENDING.sort(tracks).map { it.title })
    }

    @Test
    fun invalidDurationsAreLastWhenSortingAscending() {
        val tracks = listOf(track("zero", "zero", 0), track("long", "long", 200),
            track("short", "short", 10), track("missing", "missing", null), track("negative", "negative", -1))

        val sorted = TrackSort.DURATION_ASCENDING.sort(tracks)

        assertEquals(listOf("short", "long", "missing", "negative", "zero"), sorted.map { it.title })
    }

    @Test
    fun invalidDurationsAreLastWhenSortingDescending() {
        val tracks = listOf(track("zero", "zero", 0), track("short", "short", 10),
            track("long", "long", 200), track("missing", "missing", null), track("negative", "negative", -1))

        val sorted = TrackSort.DURATION_DESCENDING.sort(tracks)

        assertEquals(listOf("long", "short", "missing", "negative", "zero"), sorted.map { it.title })
    }

    @Test
    fun albumSortKeepsEachAlbumTogetherInDiscAndTrackOrder() {
        val tracks = listOf(
            tagged("Outro", album = "Kyogen", disc = 2, number = 1),
            tagged("Single", album = null),
            tagged("Untagged bonus", album = "Kyogen"),
            tagged("RuLe", album = "Kyogen", disc = 1, number = 4),
            tagged("Usseewa", album = "kyogen", disc = 1, number = 1),
            tagged("Ave Maria", album = "Adventure"),
        )

        assertEquals(
            listOf("Ave Maria", "Usseewa", "RuLe", "Outro", "Untagged bonus", "Single"),
            TrackSort.ALBUM_ASCENDING.sort(tracks).map { it.title },
        )
    }

    @Test
    fun artistSortGroupsByFirstArtistThenAlbumOrder() {
        val tracks = listOf(
            tagged("Two", artist = "Ado", album = "Kyogen", number = 2),
            tagged("Lone", artist = null),
            tagged("Battlefields", artist = "2WEI", album = "Battlefields"),
            tagged("One", artist = "ado", album = "Kyogen", number = 1),
        )

        assertEquals(listOf("Battlefields", "One", "Two", "Lone"), TrackSort.ARTIST_ASCENDING.sort(tracks).map { it.title })
    }

    @Test
    fun yearAndRecentlyAddedPutNewestFirstAndUnknownsLast() {
        val tracks = listOf(
            tagged("Old", year = 1998, addedAtMs = 300),
            tagged("Streamed", year = null, addedAtMs = null),
            tagged("New", year = 2022, addedAtMs = 100),
            tagged("Mid", year = 2010, addedAtMs = 200),
        )

        assertEquals(listOf("New", "Mid", "Old", "Streamed"), TrackSort.YEAR_DESCENDING.sort(tracks).map { it.title })
        assertEquals(listOf("Old", "Mid", "New", "Streamed"), TrackSort.RECENTLY_ADDED.sort(tracks).map { it.title })
    }

    @Test
    fun albumOrderPlacesNumberedTracksBeforeUntaggedOnes() {
        val tracks = listOf(
            tagged("b untagged"), tagged("Disc two", disc = 2, number = 1), tagged("a untagged"),
            tagged("Track ten", number = 10), tagged("Track two", number = 2),
        )
        assertEquals(
            listOf("Track two", "Track ten", "Disc two", "a untagged", "b untagged"),
            tracks.inAlbumOrder().map { it.title },
        )
    }

    private fun tagged(
        title: String,
        artist: String? = "Artist",
        album: String? = "Album",
        disc: Int? = null,
        number: Int? = null,
        year: Int? = null,
        addedAtMs: Long? = null,
    ) = Track(
        canonicalId = "local:$title",
        title = title,
        artists = listOfNotNull(artist?.let { ArtistRef("local-artist:$it", it) }),
        album = album?.let { AlbumRef("local-album:${it.lowercase()}", it) },
        discNumber = disc,
        trackNumber = number,
        year = year,
        addedAtMs = addedAtMs,
    )

    private fun track(title: String, canonicalId: String, durationMs: Long? = 1): Track =
        Track(canonicalId = canonicalId, title = title, artists = emptyList(), durationMs = durationMs)
}
