package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.PlaylistTracksPage
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.UnifiedSearchResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchPlaylistPresenterTest {
    private val playlist = Playlist(
        canonicalId = "spotify-playlist:mix",
        title = "Late Mix",
        ownerName = "Curator",
        trackCount = 5,
        source = ProviderEntityRef(ProviderId.SPOTIFY, "mix"),
    )

    private fun songs(vararg titles: String): List<Track> =
        titles.map { track(it, "Artist", provider = ProviderId.SPOTIFY, canonicalId = "spotify:$it") }

    /** Pages keyed by cursor; a cursor mapped to null fails once with [failure]. */
    private class FakePages(val pages: MutableMap<String?, PlaylistTracksPage?>) {
        val requested = mutableListOf<String>()
        var failure = "Spotify is rate-limiting right now."
        suspend fun load(source: ProviderEntityRef, cursor: String?): PlaylistTracksPage {
            requested += "${source.providerEntityId}@${cursor ?: "first"}"
            return pages[cursor] ?: run {
                pages.remove(cursor)
                error(failure)
            }
        }
    }

    private fun TestScope.searchWith(pages: FakePages) = SearchPresenter(
        settings = MutableStateFlow(AppSettings()),
        spotify = MutableStateFlow(ProviderState.AVAILABLE),
        youtube = MutableStateFlow(ProviderState.NOT_CONFIGURED),
        downloads = MutableStateFlow(emptyMap()),
        search = { UnifiedSearchResult(emptyList(), emptyList(), emptyList(), listOf(playlist), emptyMap()) },
        availability = { error("not used") },
        loadPlaylistPage = pages::load,
        scope = backgroundScope,
        searchDispatcher = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun openingLoadsTheFirstPageAndLoadMoreAppendsTheNext() = runTest {
        val pages = FakePages(
            mutableMapOf(
                null to PlaylistTracksPage(songs("One", "Two"), next = "2", total = 5),
                "2" to PlaylistTracksPage(songs("Three"), next = null, total = 5),
            ),
        )
        val search = searchWith(pages)
        search.openPlaylist(playlist)
        runCurrent()
        val page = search.playlist.value!!
        assertEquals(listOf("One", "Two"), page.state.value.tracks.map { it.title })
        assertEquals(PlaylistCursor.After("2"), page.state.value.cursor)

        page.loadMore()
        runCurrent()
        assertEquals(listOf("One", "Two", "Three"), page.state.value.tracks.map { it.title })
        assertTrue(page.state.value.complete)
        assertEquals(5, page.state.value.total)

        page.loadMore()
        runCurrent()
        assertEquals(listOf("mix@first", "mix@2"), pages.requested, "A complete playlist asks for nothing more")
    }

    @Test
    fun askingForTheNextPageAsSoonAsAPageLandsIsNotDropped() = runTest {
        val pages = FakePages(
            mutableMapOf(
                null to PlaylistTracksPage(emptyList(), next = "50"),
                "50" to PlaylistTracksPage(songs("Found"), next = null),
            ),
        )
        val search = searchWith(pages)
        search.openPlaylist(playlist)
        val page = search.playlist.value!!
        // Like the list's scroll trigger: react to the new cursor while the first fetch is still finishing.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            page.state.collect { if (it.cursor is PlaylistCursor.After) page.loadMore() }
        }
        runCurrent()
        assertEquals(listOf("mix@first", "mix@50"), pages.requested)
        assertEquals(listOf("Found"), page.state.value.tracks.map { it.title })
    }

    @Test
    fun aFailedPageKeepsTheLoadedSongsAndTryAgainResumesAtThatPage() = runTest {
        val pages = FakePages(
            mutableMapOf(
                null to PlaylistTracksPage(songs("One", "Two"), next = "2", total = 3),
                "2" to null,
            ),
        )
        val search = searchWith(pages)
        search.openPlaylist(playlist)
        runCurrent()
        val page = search.playlist.value!!
        page.loadMore()
        runCurrent()
        assertEquals(listOf("One", "Two"), page.state.value.tracks.map { it.title })
        assertEquals("Spotify is rate-limiting right now.", page.state.value.error)
        assertFalse(page.state.value.loading)

        pages.pages["2"] = PlaylistTracksPage(songs("Three"), next = null, total = 3)
        page.loadMore()
        runCurrent()
        assertNull(page.state.value.error)
        assertEquals(listOf("One", "Two", "Three"), page.state.value.tracks.map { it.title })
        assertEquals(listOf("mix@first", "mix@2", "mix@2"), pages.requested)
    }

    @Test
    fun playLoadsEveryRemainingPageBeforeHandingOverTheWholePlaylist() = runTest {
        val pages = FakePages(
            mutableMapOf(
                null to PlaylistTracksPage(songs("One", "Two"), next = "2"),
                "2" to PlaylistTracksPage(songs("Three", "Four"), next = "4"),
                "4" to PlaylistTracksPage(songs("Five"), next = null),
            ),
        )
        val search = searchWith(pages)
        search.openPlaylist(playlist)
        runCurrent()
        val played = mutableListOf<String>()
        search.playlist.value!!.play(startIndex = 1, shuffle = true) { tracks, index, shuffle ->
            played += "${tracks.joinToString(",") { it.title }}|$index|$shuffle"
        }
        runCurrent()
        assertEquals(listOf("One,Two,Three,Four,Five|1|true"), played)
        assertFalse(search.playlist.value!!.state.value.preparingPlay)
    }

    @Test
    fun playWithAFailingPageStartsNothingAndPlayAgainResumesAtThatPage() = runTest {
        val pages = FakePages(
            mutableMapOf(
                null to PlaylistTracksPage(songs("One", "Two"), next = "2"),
                "2" to null,
            ),
        )
        val search = searchWith(pages)
        search.openPlaylist(playlist)
        runCurrent()
        val page = search.playlist.value!!
        val played = mutableListOf<Int>()
        page.play(startIndex = 0, shuffle = false) { tracks, _, _ -> played += tracks.size }
        runCurrent()
        assertEquals(emptyList(), played, "A partial playlist must not replace the queue")
        assertEquals("Spotify is rate-limiting right now.", page.state.value.error)
        assertEquals(listOf("One", "Two"), page.state.value.tracks.map { it.title })

        pages.pages["2"] = PlaylistTracksPage(songs("Three"), next = null)
        page.play(startIndex = 0, shuffle = false) { tracks, _, _ -> played += tracks.size }
        runCurrent()
        assertEquals(listOf(3), played)
    }

    @Test
    fun backClosesThePageAndCancelsAPendingPlay() = runTest {
        val gate = CompletableDeferred<Unit>()
        val search = SearchPresenter(
            settings = MutableStateFlow(AppSettings()),
            spotify = MutableStateFlow(ProviderState.AVAILABLE),
            youtube = MutableStateFlow(ProviderState.NOT_CONFIGURED),
            downloads = MutableStateFlow(emptyMap()),
            search = { error("not used") },
            availability = { error("not used") },
            loadPlaylistPage = { _, cursor ->
                if (cursor == null) PlaylistTracksPage(songs("One"), next = "1")
                else {
                    gate.await()
                    PlaylistTracksPage(songs("Two"), next = null)
                }
            },
            scope = backgroundScope,
            searchDispatcher = StandardTestDispatcher(testScheduler),
        )
        assertFalse(search.closePlaylist(), "Nothing to close before a playlist opens")
        search.openPlaylist(playlist)
        runCurrent()
        val played = mutableListOf<Int>()
        search.playlist.value!!.play(0, shuffle = false) { tracks, _, _ -> played += tracks.size }
        runCurrent()

        assertTrue(search.closePlaylist())
        gate.complete(Unit)
        runCurrent()
        assertNull(search.playlist.value)
        assertEquals(emptyList(), played, "Leaving the page must not start playback afterwards")
    }
}
