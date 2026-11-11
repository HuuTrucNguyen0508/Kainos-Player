package com.universalmusic.player.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.hasSetTextAction
import com.universalmusic.player.data.cache.PlaybackSourceKind
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.PlaylistTracksPage
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.UnifiedSearchResult
import com.universalmusic.player.ui.screens.SearchPresenter
import com.universalmusic.player.ui.screens.SearchScreen
import com.universalmusic.player.ui.screens.testCollectionCatalog
import com.universalmusic.player.ui.screens.testCollectionStack
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Search to an in-app Spotify playlist page and back, with a fake provider standing in for the network. */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SearchPlaylistUiTest {
    private val playlist = Playlist(
        canonicalId = "spotify-playlist:night",
        title = "Night Ferry",
        ownerName = "Harbour Radio",
        trackCount = 62,
        source = ProviderEntityRef(ProviderId.SPOTIFY, "night"),
    )
    private val results = List(24) { song("Result ${it + 1}", "Searcher") }
    private val firstPage = List(50) { song(PAGE_ONE_TITLES[it % PAGE_ONE_TITLES.size] + " ${it + 1}", ARTISTS[it % ARTISTS.size]) }
    private val secondPage = List(10) { song("Late Track ${it + 51}", "Tide") }
    private val played = mutableListOf<String>()
    // Share Compose's virtual scheduler, including search debounce and provider dispatch.
    private val providerDispatcher = StandardTestDispatcher()
    private data class PageRequest(
        val cursor: String?,
        val response: CompletableDeferred<PlaylistTracksPage> = CompletableDeferred(),
    )
    private val pageRequests = mutableListOf<PageRequest>()
    private val requested get() = pageRequests.map { it.cursor }
    private val opened = mutableListOf<String>()

    private fun song(title: String, artist: String): Track =
        track(title, artist, provider = ProviderId.SPOTIFY, canonicalId = "spotify:$title")

    private fun ComposeUiTest.showSearch(withPlaylists: Boolean = true) {
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { presenter(scope, withPlaylists) }
                val pages = remember { pages(scope) }
                SearchScreen(
                    presenter = presenter,
                    onPlayTrackInList = { _, _ -> },
                    onPlayPlaylist = { tracks, index, shuffle -> played += "${tracks.size}|$index|$shuffle" },
                    trackActions = RecordedTrackActions().presenter,
                    pages = pages,
                    // Records links so no test ever starts the host's browser.
                    openLink = { opened += it },
                )
            }
        }
        onNode(hasSetTextAction()).performTextInput("night")
        mainClock.advanceTimeUntil(timeoutMillis = WAIT_MS) { onAllNodes(hasText("Result 1")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun presenter(scope: CoroutineScope, withPlaylists: Boolean = true) = SearchPresenter(
        settings = MutableStateFlow(AppSettings()),
        spotify = MutableStateFlow(ProviderState.AVAILABLE),
        youtube = MutableStateFlow(ProviderState.NOT_CONFIGURED),
        downloads = MutableStateFlow(emptyMap()),
        search = { UnifiedSearchResult(results, emptyList(), emptyList(), if (withPlaylists) listOf(playlist) else emptyList(), emptyMap()) },
        availability = { TrackAvailabilityInfo(TrackAvailability.STREAMABLE, "Spotify", PlaybackSourceKind.SPOTIFY_STREAM, "Spotify") },
        searchDispatcher = providerDispatcher,
        scope = scope,
    )

    private fun pages(scope: CoroutineScope) = testCollectionStack(
        scope,
        testCollectionCatalog(providerDispatcher, providerPlaylistPage = { _, cursor ->
            val request = PageRequest(cursor)
            pageRequests += request
            request.response.await()
        }),
    )

    private fun ComposeUiTest.text(value: String) = onNodeWithText(value, useUnmergedTree = true)

    private fun ComposeUiTest.openPlaylistFromResults() {
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Night Ferry"))
        text("Night Ferry").performClick()
        awaitRequests(listOf(null))
        releasePage(0, PlaylistTracksPage(firstPage, next = "50", total = 62))
        mainClock.advanceTimeUntil(timeoutMillis = WAIT_MS) { onAllNodes(hasText("Moonlit Quay 1")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun ComposeUiTest.awaitRequests(expected: List<String?>) {
        mainClock.advanceTimeUntil(timeoutMillis = WAIT_MS) { requested.size >= expected.size }
        assertEquals(expected, requested)
    }

    private fun ComposeUiTest.releasePage(index: Int, page: PlaylistTracksPage) {
        runOnIdle { check(pageRequests[index].response.complete(page)) }
    }

    private fun ComposeUiTest.failPage(index: Int) {
        runOnIdle {
            check(pageRequests[index].response.completeExceptionally(
                IllegalStateException("Spotify is rate-limiting right now. Wait a few minutes."),
            ))
        }
        mainClock.advanceTimeUntil(timeoutMillis = WAIT_MS) {
            onAllNodes(hasText("Try again")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.requestNextPage() {
        // Header is index 0; index 50 is the last song already loaded.
        onNode(hasScrollToIndexAction()).performScrollToIndex(50)
        awaitRequests(listOf(null, "50"))
        // The provider remains suspended however many frames the layout needs.
        text("Loading more songs").assertExists()
    }

    private fun ComposeUiTest.savePng(name: String) {
        val directory = Path.of("build", "search-playlist-proof")
        Files.createDirectories(directory)
        // An open menu is a popup with its own root; draw each popup over the window where it sits.
        val roots = onAllNodes(isRoot())
        val image = roots[0].captureToImage().toAwtImage()
        val canvas = image.createGraphics()
        for (index in 1 until roots.fetchSemanticsNodes().size) {
            val bounds = roots[index].fetchSemanticsNode().boundsInWindow
            canvas.drawImage(roots[index].captureToImage().toAwtImage(), bounds.left.toInt(), bounds.top.toInt(), null)
        }
        canvas.dispose()
        ImageIO.write(image, "png", directory.resolve(name).toFile())
    }

    @Test fun ultrawideBlankSearchIsCenteredInTheSheet() = runDesktopComposeUiTest(width = 2800, height = 900, effectContext = providerDispatcher) {
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { presenter(scope) }
                SearchScreen(presenter, { _, _ -> }, { _, _, _ -> }, RecordedTrackActions().presenter, remember { pages(scope) })
            }
        }
        val text = text("Your local files are not included here. Library has its own search for those.").fetchSemanticsNode().boundsInRoot
        kotlin.test.assertTrue(text.left > 1000f && text.top > 300f, "blank state was stranded at $text")
        savePng("search-blank-ultrawide.png")
    }

    @Test fun loneUltrawideResultsKeepTheirSourceMarksCloseToTheTitle() = runDesktopComposeUiTest(width = 2800, height = 900, effectContext = providerDispatcher) {
        showSearch(withPlaylists = false)
        val list = onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
        kotlin.test.assertTrue(list.width <= 1600f, "standalone table width was ${list.width}")
        kotlin.test.assertTrue(list.left < 20f, "standalone table must align left")
        text("Result 1").assertIsDisplayed()
        savePng("search-lone-ultrawide.png")
    }

    @Test fun aPlaylistResultOpensItsPageAndBackKeepsTheResultsScroll() = runDesktopComposeUiTest(width = 560, height = 900, effectContext = providerDispatcher) {
        showSearch()
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Night Ferry"))
        text("Spotify playlist by Harbour Radio, 62 songs").assertIsDisplayed()
        savePng("search-results-playlist-row.png")
        openPlaylistFromResults()

        text("Spotify playlist").assertIsDisplayed()
        text("62 songs").assertIsDisplayed()
        text("Moonlit Quay 1").assertIsDisplayed()
        assertEquals(listOf<String?>(null), requested, "Only the first page loads on open")
        savePng("playlist-page.png")

        onNodeWithContentDescription("More actions for Night Ferry").performClick()
        text("Open on Spotify").assertIsDisplayed()
        savePng("playlist-overflow.png")
        text("Open on Spotify").performClick()
        waitForIdle()
        assertEquals(listOf("https://open.spotify.com/playlist/night"), opened)

        // The second page loads once the list nears the end of the first.
        requestNextPage()
        releasePage(1, PlaylistTracksPage(secondPage, next = null, total = 62))
        mainClock.advanceTimeUntil(timeoutMillis = WAIT_MS) {
            onAllNodes(hasText("Late Track 51")).fetchSemanticsNodes().isNotEmpty()
        }
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Late Track 60"))
        waitForIdle()
        assertEquals(listOf(null, "50"), requested, "Scrolling near the end loads the next page")
        text("2 songs in this playlist are unavailable on Spotify and not shown.").assertIsDisplayed()
        savePng("playlist-page-end.png")

        onNodeWithContentDescription("Back to search results").performClick()
        waitForIdle()
        // The playlist row sits below 24 results, so it is only on screen if the scroll was kept.
        text("Night Ferry").assertIsDisplayed()
        text("Spotify playlist by Harbour Radio, 62 songs").assertIsDisplayed()
    }

    @Test fun aWideSearchOpensThePlaylistBesideTheResults() = runDesktopComposeUiTest(width = 2400, height = 900, effectContext = providerDispatcher) {
        showSearch()
        // Two panes: results on the left, the playlist results on the right, both on screen at once.
        text("Result 1").assertIsDisplayed()
        text("Night Ferry").assertIsDisplayed()
        text("Night Ferry").performClick()
        awaitRequests(listOf(null))
        releasePage(0, PlaylistTracksPage(firstPage, next = "50", total = 62))
        mainClock.advanceTimeUntil(timeoutMillis = WAIT_MS) { onAllNodes(hasText("Moonlit Quay 1")).fetchSemanticsNodes().isNotEmpty() }

        // The playlist page takes the right pane; the track results stay where they were.
        text("Moonlit Quay 1").assertIsDisplayed()
        text("Result 1").assertIsDisplayed()
        onNode(hasSetTextAction()).assertIsDisplayed()
        savePng("wide-playlist-beside-results.png")

        onNodeWithContentDescription("Back to search results").performClick()
        waitForIdle()
        text("Night Ferry").assertIsDisplayed()
        text("Result 1").assertIsDisplayed()
        onNodeWithText("Moonlit Quay 1", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun playAndARowQueueTheWholePlaylist() = runDesktopComposeUiTest(width = 560, height = 900, effectContext = providerDispatcher) {
        showSearch()
        openPlaylistFromResults()
        text("Play").performClick()
        awaitRequests(listOf(null, "50"))
        assertEquals(emptyList(), played, "Play waits for the whole playlist")
        releasePage(1, PlaylistTracksPage(secondPage, next = null, total = 62))
        mainClock.advanceTimeUntil(timeoutMillis = WAIT_MS) { played.isNotEmpty() }
        assertEquals(listOf("60|0|false"), played)

        text("Shuffle").performClick()
        mainClock.advanceTimeUntil(timeoutMillis = WAIT_MS) { played.size == 2 }
        text("Moonlit Quay 6").performClick()
        mainClock.advanceTimeUntil(timeoutMillis = WAIT_MS) { played.size == 3 }
        assertEquals(listOf("60|0|false", "60|0|true", "60|5|false"), played)
        assertEquals(listOf(null, "50"), requested, "Pages already in are not fetched again")
    }

    @Test fun aFailedPageKeepsTheSongsAndOffersTryAgain() = runDesktopComposeUiTest(width = 560, height = 900, effectContext = providerDispatcher) {
        showSearch()
        openPlaylistFromResults()
        requestNextPage()
        failPage(1)
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Try again"))
        waitForIdle()
        text("Showing the first 50 songs. The next page did not load. Spotify is rate-limiting right now. Wait a few minutes.")
            .assertIsDisplayed()
        text("Try again").assertIsDisplayed()
        savePng("playlist-page-error.png")
        assertEquals(listOf(null, "50"), requested, "A failed page is not retried on its own")

        text("Try again").performClick()
        awaitRequests(listOf(null, "50", "50"))
        text("Try again").assertDoesNotExist()
        failPage(2)
        assertEquals(listOf(null, "50", "50"), requested)
    }

    @Test fun aFirstPageWithNoPlayableSongsStillLoadsTheNext() = runDesktopComposeUiTest(width = 560, height = 900, effectContext = providerDispatcher) {
        showSearch()
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Night Ferry"))
        text("Night Ferry").performClick()
        awaitRequests(listOf(null))
        releasePage(0, PlaylistTracksPage(emptyList(), next = "50", total = 62))
        awaitRequests(listOf(null, "50"))
        releasePage(1, PlaylistTracksPage(secondPage, next = null, total = 62))
        mainClock.advanceTimeUntil(timeoutMillis = WAIT_MS) { onAllNodes(hasText("Late Track 51")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(listOf(null, "50"), requested)
    }

    private companion object {
        /** Virtual-time bound: a missing state fails without depending on runner speed. */
        const val WAIT_MS = 20_000L
        val PAGE_ONE_TITLES = listOf("Moonlit Quay", "Signal Fires", "Low Tide Waltz", "Gull Static", "Lantern Row")
        val ARTISTS = listOf("Neon Coast", "Tide", "The Breakwaters", "Mara Vell")
    }
}
