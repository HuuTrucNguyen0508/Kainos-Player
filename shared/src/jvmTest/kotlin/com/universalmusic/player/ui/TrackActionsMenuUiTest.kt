package com.universalmusic.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.screens.ActionTrackRow
import com.universalmusic.player.ui.screens.TrackMenuExtra
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.universalmusic.player.ui.components.TrackRow

@OptIn(ExperimentalTestApi::class)
class TrackActionsMenuUiTest {
    private val song = track("Glass Harbour", "Northern Wires", provider = ProviderId.LOCAL, canonicalId = "local:1")
    private var played = 0

    private fun ComposeUiTest.show(actions: RecordedTrackActions, track: Track = song, extras: List<TrackMenuExtra> = emptyList()) {
        setContent {
            TestTheme {
                Column { ActionTrackRow(track, actions.presenter, onClick = { played++ }, extras = extras) }
            }
        }
    }

    private fun ComposeUiTest.text(value: String) = onNodeWithText(value, useUnmergedTree = true)

    @Test fun theOverflowButtonOpensTheMenuAndHeartsTheRow() = runDesktopComposeUiTest(width = 600, height = 600) {
        val actions = RecordedTrackActions()
        show(actions)
        onNodeWithContentDescription("More actions for Glass Harbour").performClick()
        text("Play next").assertIsDisplayed()
        text("Add to queue").assertIsDisplayed()
        text("Heart").performClick()
        waitForIdle()
        assertEquals(listOf("heart:local:1:true"), actions.calls)

        // The label follows the app heart the next time the menu opens.
        onNodeWithContentDescription("More actions for Glass Harbour").performClick()
        text("Remove heart").assertIsDisplayed()
        assertEquals(0, played, "Opening the menu never plays the row")
    }

    @Test fun aLongPressOpensTheSameMenuAndPlayNextRoutesToTheQueue() = runDesktopComposeUiTest(width = 600, height = 600) {
        val actions = RecordedTrackActions()
        show(actions)
        text("Northern Wires").performTouchInput { longClick() }
        waitForIdle()
        text("Play next").performClick()
        waitForIdle()
        assertEquals(listOf("next:local:1"), actions.calls)
        assertEquals(0, played)
    }

    @Test fun addToPlaylistListsKainosPlaylistsAndCanStartANewOne() = runDesktopComposeUiTest(width = 600, height = 700) {
        val actions = RecordedTrackActions()
        actions.playlists.value = listOf(PersistedKainosPlaylist(id = "p1", title = "Late shift"))
        show(actions)
        onNodeWithContentDescription("More actions for Glass Harbour").performClick()
        text("Add to playlist").performClick()
        waitForIdle()
        text("Late shift").performClick()
        waitForIdle()
        assertEquals("add:p1:local:1", actions.calls.last())

        onNodeWithContentDescription("More actions for Glass Harbour").performClick()
        text("Add to playlist").performClick()
        waitForIdle()
        text("New playlist…").performClick()
        waitForIdle()
        onNode(hasSetTextAction()).performTextInput("Road trip")
        text("Create and add").performClick()
        waitForIdle()
        assertEquals("create:Road trip:local:1", actions.calls.last())
    }

    @Test fun aRowThatCannotPlayInAppOffersNoQueueActions() = runDesktopComposeUiTest(width = 600, height = 600) {
        val actions = RecordedTrackActions()
        var opened = false
        val webOnly = track("Live Set", "Halden", provider = ProviderId.YOUTUBE_MUSIC, playable = false, canonicalId = "yt:1")
        show(actions, webOnly, listOf(TrackMenuExtra("Open YouTube", Icons.AutoMirrored.Filled.OpenInNew) { opened = true }))
        onNodeWithContentDescription("More actions for Live Set").performClick()
        waitForIdle()
        text("Play next").assertDoesNotExist()
        text("Add to queue").assertDoesNotExist()
        text("Heart").assertIsDisplayed()
        text("Open YouTube").performClick()
        waitForIdle()
        assertEquals(true, opened)
    }

    @Test fun theMenuButtonDoesNotMakeRowsTaller() = runDesktopComposeUiTest(width = 600, height = 600) {
        val actions = RecordedTrackActions()
        val first = track("Ferric", "Halden", provider = ProviderId.LOCAL, canonicalId = "local:2")
        val last = track("Tideline", "Reyes", provider = ProviderId.LOCAL, canonicalId = "local:3")
        setContent {
            TestTheme {
                Column {
                    TrackRow(first, onClick = {}, compact = true)
                    ActionTrackRow(song, actions.presenter, onClick = {}, compact = true)
                    TrackRow(last, onClick = {}, compact = true)
                }
            }
        }
        fun top(title: String) = text(title).getBoundsInRoot().top.value
        val plainRow = top("Glass Harbour") - top("Ferric")
        val menuRow = top("Tideline") - top("Glass Harbour")
        assertEquals(plainRow, menuRow, "A row with the ⋮ is as tall as one without")
    }
}
