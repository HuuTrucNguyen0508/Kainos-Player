package com.universalmusic.player.ui.screens

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.db.*
import com.universalmusic.player.data.local.*
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.*
import java.nio.file.Files
import java.util.Properties
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LibraryPathsPerf {
    @Test fun hydrationAndColdIndex() = runBlocking {
        for (size in listOf(2_000, 20_000, 100_000)) {
            val directory = Files.createTempDirectory("kainos-library-perf-")
            fun driver() = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("kainos.db")}", Properties(), KainosDatabase.Schema)
            val locals = syntheticLibrary(size).map { t ->
                LocalTrack(t.canonicalId, t.title, t.artists.map { it.name }, t.album?.title,
                    albumGroupKey = t.album!!.canonicalId, location = "/synthetic/${t.canonicalId}.flac")
            }
            val seed = driver()
            DbLocalLibraryScanCache(KainosStorage({ seed }, clock = { 0 })).write("perf", locals)
            seed.close()
            try {
                repeat(10) { run ->
                    val start = System.nanoTime()
                    val dbDriver = driver()
                    try {
                        val cached = DbLocalLibraryScanCache(KainosStorage({ dbDriver }, clock = { 0 })).read("perf")!!
                        val provider = LocalMusicProvider(LocalTrackSource { error("No scan permitted") })
                        provider.applyCachedTracks(cached)
                        val state = deriveLibraryUiState(AppSettings(), emptyList(), emptySet(), provider.libraryTracks.value,
                            ProviderState.AVAILABLE, emptyList(), emptyList(), emptyList(), emptyList(), false, null,
                            ProviderState.UNAVAILABLE, emptyMap(), "")
                        assertEquals(size, state.songs.size)
                        println("LIBRARY hydration\t$size\t$run\t${(System.nanoTime()-start)/1e6}\t${state.songs.size}")
                        val queryStart = System.nanoTime()
                        val result = state.withQuery("lemon")
                        assertTrue(result.songs.isNotEmpty())
                        println("LIBRARY coldIndexQuery\t$size\t$run\t${(System.nanoTime()-queryStart)/1e6}\t${result.songs.size}")
                    } finally { dbDriver.close() }
                }
            } finally { directory.toFile().deleteRecursively() }
        }
    }

    @Test fun indexPreservesResults() {
        val tracks = syntheticLibrary(2_000)
        for (sort in TrackSort.entries) for (favoritesOnly in listOf(false, true)) {
            val hearts = tracks.take(100).map { it.canonicalId }.toSet()
            val state = deriveLibraryUiState(AppSettings(librarySongSort = sort, libraryFavoritesOnly = favoritesOnly),
                tracks, hearts, tracks, ProviderState.AVAILABLE, emptyList(), emptyList(), emptyList(), emptyList(),
                false, null, ProviderState.UNAVAILABLE, emptyMap(), "")
            for (query in listOf("", "LÉMON", "kmn", "night 1", "artist 2", "absent xyz", "lemon a")) {
                val needle = libraryNeedle(query)
                val result = state.withQuery(query)
                assertEquals(libraryDisplaySongs(state.queueSongs, needle), result.songs)
                assertEquals(libraryFilteredAlbums(libraryLocalAlbums(tracks, favoritesOnly, hearts), needle), result.albums)
                assertEquals(libraryFilteredArtists(libraryLocalArtists(tracks, favoritesOnly, hearts), needle), result.artists)
                assertSame(state.queueSongs, result.queueSongs)
                assertEquals(result, result.withQuery("").withQuery(query))
            }
        }
    }
}
