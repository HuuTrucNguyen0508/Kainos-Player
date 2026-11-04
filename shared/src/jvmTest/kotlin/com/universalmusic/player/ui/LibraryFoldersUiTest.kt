package com.universalmusic.player.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.universalmusic.player.data.cache.PlaybackSourceKind
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.screens.LibraryPresenter
import com.universalmusic.player.ui.screens.LibraryScreen
import com.universalmusic.player.ui.screens.fileTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalTestApi::class)
class LibraryFoldersUiTest {
    private val played = mutableListOf<String>()
    private val shuffled = mutableListOf<String>()
    private val pins = MutableStateFlow<List<PersistedHomePin>>(emptyList())
    private var back: (() -> Unit)? = null

    /** /music holds 40 album folders of two songs each; Album 07 also has a Bonus subfolder. */
    private val library: List<Track> = (0 until 40).flatMap { n ->
        val album = "/music/Album ${n.toString().padStart(2, '0')}"
        listOf(fileTrack("$album/1 Opening.flac"), fileTrack("$album/2 Closing.flac"))
    } + fileTrack("/music/Album 07/Bonus/Demo.flac")

    private fun presenter(scope: CoroutineScope): LibraryPresenter {
        val settings = MutableStateFlow(AppSettings(localMusicFoldersConfigured = true, localMusicFolders = listOf("/music")))
        return LibraryPresenter(
            settings = settings,
            updateSettings = { settings.value = it(settings.value) },
            savedTracks = MutableStateFlow(emptyList()),
            favorites = MutableStateFlow(emptySet()),
            localTracks = MutableStateFlow(library),
            localState = MutableStateFlow(ProviderState.AVAILABLE),
            spotifyTracks = MutableStateFlow(emptyList()),
            spotifyPlaylists = MutableStateFlow(emptyList()),
            kainosPlaylistRows = MutableStateFlow(emptyList()),
            homePins = pins,
            spotifyLibraryLoading = MutableStateFlow(false),
            spotifyLibraryError = MutableStateFlow(null),
            spotifyState = MutableStateFlow(ProviderState.UNAVAILABLE),
            downloads = MutableStateFlow(emptyMap()),
            onTrackAvailability = { TrackAvailabilityInfo(TrackAvailability.AVAILABLE_LOCALLY, "Local", PlaybackSourceKind.LOCAL_FILE, "Local") },
            onPinHome = { kind, target, title, _, _, _, _ ->
                pins.value = pins.value + PersistedHomePin(id = target, kind = kind, targetId = target, title = title)
            },
            onUnpinHome = { kind, target -> pins.value = pins.value.filterNot { it.kind == kind && it.targetId == target } },
            scope = scope,
        )
    }

    private fun ComposeUiTest.showFolders() {
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember { presenter(scope) }
                LibraryScreen(
                    MutableStateFlow(""), { "" }, presenter,
                    { tracks, index -> played += "${tracks.joinToString { it.title }} @$index" },
                    RecordedTrackActions().presenter,
                    onShuffleTracks = { tracks -> shuffled += tracks.joinToString { it.title } },
                    onInnerBack = { back = it },
                )
            }
        }
        onNodeWithText("Folders").performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.goBack() {
        runOnIdle { back!!.invoke() }
        waitForIdle()
    }

    @Test fun backGoesUpOneLevelAndLandsWhereTheListWas() = runDesktopComposeUiTest(width = 800, height = 700) {
        showFolders()
        assertNull(back, "Nothing to go back to at the list of music folders")
        onNodeWithText("music").performClick()
        waitForIdle()
        onNodeWithText("40 folders, 81 tracks").assertIsDisplayed()

        onNode(hasScrollToIndexAction()).performScrollToIndex(29) // Two rows above, so the sticky path header does not cover Album 30
        waitForIdle()
        onNodeWithText("Album 30").performClick()
        waitForIdle()
        onNodeWithText("1 Opening", useUnmergedTree = true).assertIsDisplayed()

        goBack()
        onNodeWithText("Album 30").assertIsDisplayed()
        onNodeWithText("Album 00").assertDoesNotExist()

        goBack()
        onNodeWithText("1 music folder", useUnmergedTree = true).assertIsDisplayed()
        assertNull(back)
    }

    @Test fun foldersPlayShuffleAndPinWithEverythingBelowThem() = runDesktopComposeUiTest(width = 800, height = 700) {
        showFolders()
        onNodeWithText("music").performClick()
        waitForIdle()
        onNode(hasScrollToIndexAction()).performScrollToIndex(6)
        waitForIdle()

        onNodeWithContentDescription("Play Album 07").performClick()
        onNodeWithContentDescription("Shuffle Album 07").performClick()
        onNodeWithContentDescription("Pin Album 07 to Home").performClick()
        waitForIdle()
        assertEquals(listOf("1 Opening, 2 Closing, Demo @0"), played)
        assertEquals(listOf("1 Opening, 2 Closing, Demo"), shuffled)
        assertEquals(listOf(HomePinKind.LOCAL_FOLDER to "/music/Album 07"), pins.value.map { it.kind to it.targetId })
        onNodeWithContentDescription("Unpin Album 07 from Home").assertIsDisplayed()

        // Inside, the path leads back up, and a tapped song plays the files of its own folder.
        onNodeWithText("Album 07").performClick()
        waitForIdle()
        onNodeWithText("1 folder, 3 tracks").assertIsDisplayed()
        onNodeWithText("2 Closing", useUnmergedTree = true).performClick()
        assertEquals("1 Opening, 2 Closing @1", played.last())
        onNodeWithText("Shuffle").performClick()
        assertEquals("1 Opening, 2 Closing, Demo", shuffled.last())

        onNodeWithText("Bonus").performClick()
        waitForIdle()
        onNodeWithContentDescription("Up to Album 07").assertIsDisplayed()
        onNodeWithText("music").performClick()
        waitForIdle()
        onNodeWithText("40 folders, 81 tracks").assertIsDisplayed()
    }
}
