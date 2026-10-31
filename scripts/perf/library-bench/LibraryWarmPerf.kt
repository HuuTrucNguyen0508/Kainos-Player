package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.*
import kotlinx.coroutines.*
import kotlin.test.*

class LibraryWarmPerf {
    @Test fun firstQuery() = runBlocking {
        for (size in listOf(20_000, 100_000)) {
            val tracks = syntheticLibrary(size)
            repeat(13) { run ->
                for (warm in if (run % 2 == 0) listOf(false, true) else listOf(true, false)) {
                    val state = deriveLibraryUiState(AppSettings(), tracks, emptySet(), tracks,
                        ProviderState.AVAILABLE, emptyList(), emptyList(), emptyList(), emptyList(), false, null,
                        ProviderState.UNAVAILABLE, emptyMap(), "")
                    if (warm) withContext(Dispatchers.Default) { state.searchIndex.warm() }
                    val start = System.nanoTime()
                    val result = state.withQuery("lemon")
                    val ms = (System.nanoTime()-start)/1e6
                    assertEquals(size/5, result.songs.size)
                    assertSame(state.queueSongs, result.queueSongs)
                    if (run >= 3) println("LIBRARY firstQuery:${if(warm) "backgroundWarm" else "lazy"}\t$size\t${run-3}\t$ms\t${result.songs.size}")
                }
            }
        }
    }
}
