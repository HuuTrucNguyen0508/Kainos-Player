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
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

/** Search to an in-app Spotify playlist page and back, with a fake provider standing in for the network. */
@OptIn(ExperimentalTestApi::class)
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
    private val requested = mutableListOf<String?>()
    private val opened = mutableListOf<String>()

    private fun song(title: String, artist: String): Track =
        track(title, artist, provider = ProviderId.SPOTIFY, canonicalId = "spotify:$title")

    private fun ComposeUiTest.showSearch(secondPageFails: Boolean = false) {
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { presenter(scope, secondPageFails) }
                SearchScreen(
                    presenter = presenter,
                    onPlayTrackInList = { _, _ -> },
                    onPlayPlaylist = { tracks, index, shuffle -> played += "${tracks.size}|$index|$shuffle" },
                    trackActions = RecordedTrackActions().presenter,
                    // Records links so no test ever starts the host's browser.
                    openLink = { opened += it },
                )
            }
        }
        onNode(hasSetTextAction()).performTextInput("night")
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("Result 1")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun presenter(scope: CoroutineScope, secondPageFails: Boolean) = SearchPresenter(
        settings = MutableStateFlow(AppSettings()),
        spotify = MutableStateFlow(ProviderState.AVAILABLE),
        youtube = MutableStateFlow(ProviderState.NOT_CONFIGURED),
        downloads = MutableStateFlow(emptyMap()),
        search = { UnifiedSearchResult(results, emptyList(), emptyList(), listOf(playlist), emptyMap()) },
        availability = { TrackAvailabilityInfo(TrackAvailability.STREAMABLE, "Spotify", PlaybackSourceKind.SPOTIFY_STREAM, "Spotify") },
        loadPlaylistPage = { _, cursor ->
            requested += cursor
            when {
                cursor == null -> PlaylistTracksPage(firstPage, next = "50", total = 62)
                secondPageFails -> error("Spotify is rate-limiting right now. Wait a few minutes.")
                else -> PlaylistTracksPage(secondPage, next = null, total = 62)
            }
        },
        scope = scope,
    )

    private fun ComposeUiTest.text(value: String) = onNodeWithText(value, useUnmergedTree = true)

    private fun ComposeUiTest.openPlaylistFromResults() {
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Night Ferry"))
        text("Night Ferry").performClick()
        waitUntil(timeoutMillis = 5_000) { onAllNodes(hasText("By Harbour Radio")).fetchSemanticsNodes().isNotEmpty() }
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

    @Test fun aPlaylistResultOpensItsPageAndBackKeepsTheResultsScroll() = runDesktopComposeUiTest(width = 560, height = 900) {
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

    @Test fun playAndARowQueueTheWholePlaylist() = runDesktopComposeUiTest(width = 560, height = 900) {
        showSearch()
        openPlaylistFromResults()
        text("Play").performClick()
        waitUntil(timeoutMillis = 5_000) { played.isNotEmpty() }
        assertEquals(listOf("60|0|false"), played)

        text("Shuffle").performClick()
        waitUntil(timeoutMillis = 5_000) { played.size == 2 }
        text("Moonlit Quay 6").performClick()
        waitUntil(timeoutMillis = 5_000) { played.size == 3 }
        assertEquals(listOf("60|0|false", "60|0|true", "60|5|false"), played)
        assertEquals(listOf(null, "50"), requested, "Pages already in are not fetched again")
    }

    @Test fun aFailedPageKeepsTheSongsAndOffersTryAgain() = runDesktopComposeUiTest(width = 560, height = 900) {
        showSearch(secondPageFails = true)
        openPlaylistFromResults()
        onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Try again"))
        waitForIdle()
        text("Showing the first 50 songs. The next page did not load. Spotify is rate-limiting right now. Wait a few minutes.")
            .assertIsDisplayed()
        text("Try again").assertIsDisplayed()
        savePng("playlist-page-error.png")
        assertEquals(listOf(null, "50"), requested, "A failed page is not retried on its own")

        text("Try again").performClick()
        waitForIdle()
        assertEquals(listOf(null, "50", "50"), requested)
    }

    private companion object {
        val PAGE_ONE_TITLES = listOf("Moonlit Quay", "Signal Fires", "Low Tide Waltz", "Gull Static", "Lantern Row")
        val ARTISTS = listOf("Neon Coast", "Tide", "The Breakwaters", "Mara Vell")
    }
}
