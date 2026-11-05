package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.*
import kotlin.test.Test
import kotlin.test.assertEquals

/** Deterministic, mixed-case/diacritic catalog. No real files, network or user data. */
internal fun syntheticLibrary(size: Int): List<Track> = List(size) { i ->
    val artist = i % 200
    val album = i / 12
    Track(
        canonicalId = "local:$i",
        title = "${listOf("Lémon", "Kimi no Namae", "Night Flight", "Étude", "Blue Sky")[i % 5]} ${size - i}",
        artists = listOf(ArtistRef("artist:$artist", "Artist $artist")),
        album = AlbumRef("album:$album", "Album $album"),
        durationMs = 180_000,
    )
}

class LibraryPerf {
    @Test fun measure() {
        println("LIBRARY metric\tsize\trun\tms\trows")
        for (size in listOf(2_000, 20_000, 100_000)) {
            val tracks = syntheticLibrary(size)
            val hearts = tracks.filterIndexed { i, _ -> i % 4 == 0 }.map { it.canonicalId }.toSet()
            fun derive(sort: TrackSort = TrackSort.NAME_ASCENDING, favorites: Boolean = false, query: String = "") = deriveLibraryUiState(
                AppSettings(librarySongSort = sort, libraryFavoritesOnly = favorites), tracks, hearts, tracks,
                ProviderState.AVAILABLE, emptyList(), emptyList(), emptyList(), emptyList(), false, null,
                ProviderState.UNAVAILABLE, emptyMap(), query,
            )
            val base = derive()
            val queries = listOf("l", "le", "lem", "lemo", "lemon", "lemon a", "lemon ar", "kmn", "night 1", "absent xyz")
            fun sample(run: Int, report: Boolean) {
                fun timed(metric: String, block: () -> Int) {
                    val start = System.nanoTime()
                    val rows = block()
                    val elapsed = (System.nanoTime() - start) / 1e6
                    if (report) println("LIBRARY $metric\t$size\t$run\t$elapsed\t$rows")
                }
                for (query in queries) timed("query:$query") {
                    val result = base.withQuery(query)
                    assertEquals(base.queueSongs, result.queueSongs)
                    result.songs.size + result.albums.size + result.artists.size
                }
                timed("derive") { derive().songs.size.also { assertEquals(size, it) } }
                timed("sort") { derive(TrackSort.NAME_DESCENDING).songs.size.also { assertEquals(size, it) } }
                timed("filter") { derive(favorites = true).songs.size.also { assertEquals(size / 4, it) } }
            }
            repeat(5) { sample(it, false) }
            repeat(10) { sample(it, true) }
        }
    }
}
