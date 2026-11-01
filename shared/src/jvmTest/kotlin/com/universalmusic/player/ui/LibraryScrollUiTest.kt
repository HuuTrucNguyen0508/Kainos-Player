package com.universalmusic.player.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.material3.Text
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ScrollWheel
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.universalmusic.player.data.cache.PlaybackSourceKind
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.ui.screens.LibraryPresenter
import com.universalmusic.player.ui.screens.LibraryScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class LibraryScrollUiTest {
    private fun presenter(tracks: List<Track>, favorites: Set<String>, scope: CoroutineScope): LibraryPresenter {
        val settings = MutableStateFlow(AppSettings())
        return LibraryPresenter(
            settings = settings,
            updateSettings = { settings.value = it(settings.value) },
            savedTracks = MutableStateFlow(emptyList()),
            favorites = MutableStateFlow(favorites),
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

    private fun ComposeUiTest.showLibrary(tracks: List<Track>, favorites: Set<String> = emptySet()) {
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { presenter(tracks, favorites, scope) }
                LibraryScreen(MutableStateFlow(""), { "" }, presenter, { _, _ -> })
            }
        }
    }

    private fun ComposeUiTest.songList() = onNode(hasScrollToIndexAction())

    @Test fun togglingAFilterGoesToTheTopAndStaysThere() = runDesktopComposeUiTest(width = 800, height = 700) {
        showLibrary(localTracks(120))
        songList().performScrollToIndex(90)
        waitForIdle()
        onNodeWithText("Song 000", useUnmergedTree = true).assertDoesNotExist()

        onNodeWithText("Local files only", useUnmergedTree = true).performClick()
        waitForIdle()
        onNodeWithText("Song 000", useUnmergedTree = true).assertIsDisplayed()

        // The old clamp fired again on later frames: the list must not snap back on the next one.
        songList().performScrollToIndex(5)
        waitForIdle()
        onNodeWithText("Song 000", useUnmergedTree = true).assertDoesNotExist()
    }

    /** Real wheel input, because the regression lived in the nested scroll the wheel goes through. */
    private fun ComposeUiTest.wheel(rows: Float) {
        songList().performMouseInput {
            moveTo(center)
            scroll(rows, ScrollWheel.Vertical)
        }
        mainClock.advanceTimeBy(600)
        waitForIdle()
    }

    private fun ComposeUiTest.topOf(title: String) =
        onNodeWithText(title, useUnmergedTree = true).getBoundsInRoot().top.value

    @Test fun aShortFavoritesListScrollsBothWays() = runDesktopComposeUiTest(width = 800, height = 480) {
        val library = localTracks(40)
        showLibrary(library, favorites = library.take(12).map { it.canonicalId }.toSet())
        onNodeWithText("Favorites only", useUnmergedTree = true).performClick()
        waitForIdle()
        val resting = topOf("Song 000")

        // Down: the header collapses first, then the rows move.
        wheel(4f)
        wheel(4f)
        val scrolledDown = onAllNodesWithText("Song 000", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() ||
            topOf("Song 000") < resting
        assertTrue(scrolledDown, "Wheel down moves the list")

        // Up: the first row comes back, and nothing yanks the list on a later frame.
        repeat(6) { wheel(-4f) }
        onNodeWithText("Song 000", useUnmergedTree = true).assertIsDisplayed()
        val back = topOf("Song 000")
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertEquals(back, topOf("Song 000"), "The list stays where the wheel left it")
    }

    @Test fun comingBackKeepsTheScrollPosition() = runDesktopComposeUiTest(width = 800, height = 700) {
        var inLibrary by mutableStateOf(true)
        val library = localTracks(120)
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { presenter(library, emptySet(), scope) }
                val tabs = rememberSaveableStateHolder()
                if (inLibrary) {
                    tabs.SaveableStateProvider("Library") {
                        LibraryScreen(MutableStateFlow(""), { "" }, presenter, { _, _ -> })
                    }
                } else {
                    Text("Now Playing")
                }
            }
        }
        songList().performScrollToIndex(80)
        waitForIdle()
        onNodeWithText("Song 079", useUnmergedTree = true).assertIsDisplayed()

        inLibrary = false
        waitForIdle()
        onNodeWithText("Now Playing").assertIsDisplayed()

        inLibrary = true
        waitForIdle()
        onNodeWithText("Song 079", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithText("Song 000", useUnmergedTree = true).assertDoesNotExist()
    }
}
