package com.universalmusic.player.ui

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.universalmusic.player.data.cache.PlaybackSourceKind
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.queue.QueueController
import com.universalmusic.player.ui.screens.LibraryPresenter
import com.universalmusic.player.ui.screens.LibraryScreen
import com.universalmusic.player.ui.screens.QueueContent
import com.universalmusic.player.ui.screens.QueuePresenter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class QueueAndShuffleUiTest {
    private fun song(title: String) =
        track(title, "Northern Wires", album = "Harbour Lights", provider = ProviderId.LOCAL, canonicalId = "local:$title")

    private val songs = listOf(song("One"), song("Two"), song("Three"))

    private class QueueRig(tracks: List<Track>, radio: ProviderId?) {
        var n = 0
        val controller = QueueController { "item-${n++}" }.apply { playNow(tracks) }
        val radio = MutableStateFlow(radio)
        val actions = RecordedTrackActions(openCollection = {})
        fun titles() = controller.queue.value.items.map { it.track.title }
    }

    private fun ComposeUiTest.showQueue(rig: QueueRig, dense: Boolean = false) {
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember {
                    QueuePresenter(
                        queue = rig.controller.queue,
                        playIndex = { rig.controller.jumpTo(it) },
                        clearQueue = rig.controller::clear,
                        undoEdit = rig.controller::undo,
                        removeItem = { rig.controller.remove(it) },
                        moveItem = rig.controller::moveInPlaybackOrder,
                        savePlaylist = { name, _ -> PersistedKainosPlaylist("p", name) },
                        scope = scope,
                        radio = rig.radio,
                        clearUpcomingItems = { rig.controller.clearUpcoming() },
                        stopRadioAction = { rig.radio.value = null },
                    )
                }
                QueueContent(presenter, rig.actions.presenter, dense) {}
            }
        }
    }

    private fun ComposeUiTest.text(value: String) = onNodeWithText(value, useUnmergedTree = true)

    @Test fun queueRowsOpenTheSharedSongMenu() = runDesktopComposeUiTest(width = 600, height = 900) {
        val rig = QueueRig(songs, radio = null)
        showQueue(rig)
        onNodeWithContentDescription("More actions for Two").performClick()
        text("Play next").assertIsDisplayed()
        text("Go to album").assertIsDisplayed()
        text("Go to artist").assertIsDisplayed()
        text("Add to queue").performClick()
        waitForIdle()
        assertEquals(listOf("queue:local:Two"), rig.actions.calls)
    }

    @Test fun denseRowsMoveFromTheMenu() = runDesktopComposeUiTest(width = 600, height = 900) {
        val rig = QueueRig(songs, radio = null)
        showQueue(rig, dense = true)
        text("Move up").assertDoesNotExist()
        onNodeWithContentDescription("More actions for Two").performClick()
        text("Move down").performClick()
        waitForIdle()
        assertEquals(listOf("One", "Three", "Two"), rig.titles())
        // The first row has nothing above it to move past.
        onNodeWithContentDescription("More actions for One").performClick()
        text("Move up").assertDoesNotExist()
        text("Move down").assertIsDisplayed()
    }

    @Test fun clearUpcomingKeepsTheCurrentSongAndUndoBringsTheRestBack() = runDesktopComposeUiTest(width = 600, height = 900) {
        val rig = QueueRig(songs, radio = null)
        showQueue(rig)
        text("Clear upcoming").performClick()
        waitForIdle()
        assertEquals(listOf("One"), rig.titles())
        text("One").assertIsDisplayed()
        text("Two").assertDoesNotExist()
        text("Cleared 2 upcoming songs").assertIsDisplayed()
        text("Clear upcoming").assertDoesNotExist()

        text("Undo").performClick()
        waitForIdle()
        assertEquals(listOf("One", "Two", "Three"), rig.titles())
        text("Three").assertIsDisplayed()
    }

    @Test fun theRadioFooterNamesTheRadioAndStopsIt() = runDesktopComposeUiTest(width = 600, height = 900) {
        val rig = QueueRig(songs, radio = ProviderId.SPOTIFY)
        showQueue(rig)
        text("Spotify radio continues after these").assertIsDisplayed()

        text("Clear upcoming").performClick()
        waitForIdle()
        text("Spotify radio continues after this song").assertIsDisplayed()

        text("Stop radio").performClick()
        waitForIdle()
        assertEquals(null, rig.radio.value)
        text("Spotify radio continues after this song").assertDoesNotExist()
        text("Stop radio").assertDoesNotExist()
        assertEquals(listOf("One"), rig.titles(), "stopping the radio leaves the queue alone")
    }

    @Test fun noRadioMeansNoFooter() = runDesktopComposeUiTest(width = 600, height = 900) {
        showQueue(QueueRig(songs, radio = null))
        text("Stop radio").assertDoesNotExist()
    }

    @Test fun shuffleAllPlaysWhatTheChipsDefineOnceEach() = runDesktopComposeUiTest(width = 900, height = 900) {
        val file = track("Midnight City", "M83", durationMs = 243_000, provider = ProviderId.LOCAL, canonicalId = "local:midnight")
        val like = track("Midnight City", "M83", durationMs = 243_400, provider = ProviderId.SPOTIFY, canonicalId = "spotify:midnight")
        val other = track("Wait", "M83", durationMs = 343_000, provider = ProviderId.LOCAL, canonicalId = "local:wait")
        val unhearted = track("Outro", "M83", durationMs = 250_000, provider = ProviderId.LOCAL, canonicalId = "local:outro")
        val shuffled = mutableListOf<List<String>>()
        val played = mutableListOf<List<String>>()
        setContent {
            TestTheme {
                val scope = rememberCoroutineScope()
                val presenter = remember {
                    val settings = MutableStateFlow(AppSettings(libraryFavoritesOnly = true))
                    LibraryPresenter(
                        settings = settings,
                        updateSettings = { settings.value = it(settings.value) },
                        savedTracks = MutableStateFlow(emptyList()),
                        // The Spotify copy's heart counts for the file Library lists.
                        favorites = MutableStateFlow(setOf("spotify:midnight", "local:wait")),
                        localTracks = MutableStateFlow(listOf(file, other, unhearted)),
                        localState = MutableStateFlow(ProviderState.AVAILABLE),
                        spotifyTracks = MutableStateFlow(listOf(like)),
                        spotifyPlaylists = MutableStateFlow(emptyList()),
                        kainosPlaylistRows = MutableStateFlow(emptyList()),
                        homePins = MutableStateFlow(emptyList()),
                        spotifyLibraryLoading = MutableStateFlow(false),
                        spotifyLibraryError = MutableStateFlow(null),
                        spotifyState = MutableStateFlow(ProviderState.AVAILABLE),
                        downloads = MutableStateFlow(emptyMap()),
                        onTrackAvailability = { TrackAvailabilityInfo(TrackAvailability.AVAILABLE_LOCALLY, "Local", PlaybackSourceKind.LOCAL_FILE, "Local") },
                        scope = scope,
                    )
                }
                LibraryScreen(
                    MutableStateFlow(""), { "" }, presenter,
                    onPlayTracks = { tracks, _ -> played += tracks.map { it.canonicalId } },
                    trackActions = RecordedTrackActions().presenter,
                    onShuffleTracks = { tracks -> shuffled += tracks.map { it.canonicalId } },
                )
            }
        }
        waitForIdle()
        onNodeWithContentDescription("Shuffle").performClick()
        text("Play favorites").performClick()
        waitForIdle()
        assertEquals(listOf(listOf("local:midnight", "local:wait")), shuffled)
        assertEquals(shuffled, played, "Shuffle takes exactly the songs Play favorites plays")
    }
}
