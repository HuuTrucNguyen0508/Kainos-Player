package com.universalmusic.player.ui.screens

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.PlaylistTracksPage
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class CollectionPresenterTest {
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

    private fun TestScope.searchWith(pages: FakePages) = stackWith(pages::load)

    private fun TestScope.stackWith(load: suspend (ProviderEntityRef, String?) -> PlaylistTracksPage) =
        testCollectionStack(
            backgroundScope,
            testCollectionCatalog(StandardTestDispatcher(testScheduler), providerPlaylistPage = load),
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
        search.openFresh(CollectionRef.ProviderPlaylist(playlist))
        runCurrent()
        val page = search.top!!.presenter
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
        search.openFresh(CollectionRef.ProviderPlaylist(playlist))
        val page = search.top!!.presenter
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
        search.openFresh(CollectionRef.ProviderPlaylist(playlist))
        runCurrent()
        val page = search.top!!.presenter
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
        search.openFresh(CollectionRef.ProviderPlaylist(playlist))
        runCurrent()
        val played = mutableListOf<String>()
        search.top!!.presenter.play(startIndex = 1, shuffle = true) { tracks, index, shuffle ->
            played += "${tracks.joinToString(",") { it.title }}|$index|$shuffle"
        }
        runCurrent()
        assertEquals(listOf("One,Two,Three,Four,Five|1|true"), played)
        assertFalse(search.top!!.presenter.state.value.preparingPlay)
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
        search.openFresh(CollectionRef.ProviderPlaylist(playlist))
        runCurrent()
        val page = search.top!!.presenter
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
        val search = stackWith { _, cursor ->
            if (cursor == null) PlaylistTracksPage(songs("One"), next = "1")
            else {
                gate.await()
                PlaylistTracksPage(songs("Two"), next = null)
            }
        }
        assertFalse(search.back(), "Nothing to close before a playlist opens")
        search.openFresh(CollectionRef.ProviderPlaylist(playlist))
        runCurrent()
        val played = mutableListOf<Int>()
        search.top!!.presenter.play(0, shuffle = false) { tracks, _, _ -> played += tracks.size }
        runCurrent()

        assertTrue(search.back())
        gate.complete(Unit)
        runCurrent()
        assertNull(search.top)
        assertEquals(emptyList(), played, "Leaving the page must not start playback afterwards")
    }
}
