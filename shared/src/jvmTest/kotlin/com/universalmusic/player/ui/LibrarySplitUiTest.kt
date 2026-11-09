package com.universalmusic.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.cache.PlaybackSourceKind
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.screens.LibraryPresenter
import com.universalmusic.player.ui.screens.LibraryScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class LibrarySplitUiTest {
    private fun presenter(tracks: List<Track>, scope: CoroutineScope): LibraryPresenter {
        val settings = MutableStateFlow(AppSettings())
        return LibraryPresenter(
            settings = settings,
            updateSettings = { settings.value = it(settings.value) },
            savedTracks = MutableStateFlow(emptyList()),
            favorites = MutableStateFlow(emptySet()),
            localTracks = MutableStateFlow(tracks),
            localState = MutableStateFlow(ProviderState.AVAILABLE),
            spotifyTracks = MutableStateFlow(emptyList()),
            spotifyPlaylists = MutableStateFlow(emptyList()),
            kainosPlaylistRows = MutableStateFlow(emptyList()),
            homePins = MutableStateFlow(emptyList()),
            spotifyLibraryLoading = MutableStateFlow(false),
            spotifyLibraryError = MutableStateFlow(null),
            spotifyState = MutableStateFlow(ProviderState.UNAVAILABLE),
            downloads = MutableStateFlow(emptyMap()),
            onTrackAvailability = { TrackAvailabilityInfo(TrackAvailability.AVAILABLE_LOCALLY, "Local", PlaybackSourceKind.LOCAL_FILE, "Local") },
            scope = scope,
        )
    }

    /** Each song by its own artist, so the Artists tab is as long as Songs. */
    private val library = List(120) { index ->
        val n = index.toString().padStart(3, '0')
        track("Song $n", "Artist $n", album = "Album $n", provider = ProviderId.LOCAL, canonicalId = "local:$index")
    }

    @Test fun noAlbumsDefaultsToArtistsAndCompactFiltersRemainReachableAfterScrolling() = runDesktopComposeUiTest(width = 2400, height = 800) {
        var width by mutableStateOf(2200.dp)
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { presenter(library.map { it.copy(album = null) }, scope) }
                Box(Modifier.width(width).fillMaxHeight()) {
                    LibraryScreen(MutableStateFlow(""), { "" }, presenter, { _, _ -> }, RecordedTrackActions().presenter)
                }
            }
        }
        onNodeWithText("No albums").assertDoesNotExist()
        // Artists is shown beside Songs, and the explicit Albums choice still wins.
        onNodeWithText("Albums").performClick()
        waitForIdle()
        onNodeWithText("No albums").assertIsDisplayed()
        width = 1600.dp
        waitForIdle()
        onNodeWithText("Songs").performClick()
        onNode(hasScrollToIndexAction()).performScrollToIndex(80)
        onNodeWithText("Favorites only").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText("Favorites only").assertIsDisplayed().performClick()
        waitForIdle()
        onNodeWithText("Song 000", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun crossingTheSplitKeepsTheTabAndEveryScroll() = runDesktopComposeUiTest(width = 2400, height = 800) {
        var width by mutableStateOf(1600.dp)
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { presenter(library, scope) }
                Box(Modifier.width(width).fillMaxHeight()) {
                    LibraryScreen(MutableStateFlow(""), { "" }, presenter, { _, _ -> }, RecordedTrackActions().presenter)
                }
            }
        }
        // One pane: scroll Songs, then pick Artists and scroll it too.
        onNode(hasScrollToIndexAction()).performScrollToIndex(60)
        waitForIdle()
        onNodeWithText("Song 059", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Artists").performClick()
        waitForIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(40)
        waitForIdle()
        onNodeWithText("Artist 040", useUnmergedTree = true).assertIsDisplayed()

        // Two panes: Songs on the left where it was left, Artists on the right where it was left.
        width = 2200.dp
        waitForIdle()
        onNodeWithText("Song 059", useUnmergedTree = true).assertIsDisplayed()
        // Songs sits at 059 onward, so the only Artist 040 on screen is the Artists list's.
        onNodeWithText("Artist 040", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Artist 000", useUnmergedTree = true).assertDoesNotExist()
        onNodeWithText("Songs").assertDoesNotExist()

        // Back to one pane: Artists is still the selected tab, still scrolled.
        width = 1600.dp
        waitForIdle()
        onNodeWithText("Artist 040", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Artist 000", useUnmergedTree = true).assertDoesNotExist()
        onNode(hasText("Songs")).performClick()
        waitForIdle()
        onNodeWithText("Song 059", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun pressingSongsInTheSplitMakesSongsTheNarrowTab() = runDesktopComposeUiTest(width = 2400, height = 800) {
        var width by mutableStateOf(2200.dp)
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { presenter(library, scope) }
                Box(Modifier.width(width).fillMaxHeight()) {
                    LibraryScreen(MutableStateFlow(""), { "" }, presenter, { _, _ -> }, RecordedTrackActions().presenter)
                }
            }
        }
        onNodeWithText("Folders").performClick()
        waitForIdle()
        onNodeWithText("Song 000", useUnmergedTree = true).performClick()
        waitForIdle()
        width = 1600.dp
        waitForIdle()
        // Songs is the narrow tab now; the right pane's choice is remembered for the next split.
        onNodeWithText("Song 000", useUnmergedTree = true).assertIsDisplayed()
        width = 2200.dp
        waitForIdle()
        onNodeWithText("Folders").assertIsDisplayed()
        onNodeWithText("Albums").assertIsDisplayed()
    }
}
