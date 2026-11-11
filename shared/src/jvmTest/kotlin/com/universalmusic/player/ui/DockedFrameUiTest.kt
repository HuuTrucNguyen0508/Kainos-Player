package com.universalmusic.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.settings.NowPlayingDock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DockedFrameUiTest {
    private var dock by mutableStateOf(DockEdge.RIGHT)
    private var layoutBand by mutableStateOf(DesktopLayoutBand.Wide)
    private var dockSetting by mutableStateOf(NowPlayingDock.RIGHT)
    private var portrait by mutableStateOf(false)
    private var hidePane by mutableStateOf(false)
    private val drag = DockDragState()
    private val docked = mutableListOf<DockEdge>()

    /** The app's frame with a saved, scrollable screen in the content slot, as Library sits in AppScaffold. */
    private var searchEscapes = 0
    private var queueColumn by mutableStateOf(false)

    private fun ComposeUiTest.showFrame(searchField: Boolean = false) {
        setContent {
            TestTheme {
                val tabs = rememberSaveableStateHolder()
                val dock = if (dockSetting == NowPlayingDock.AUTO) resolveDockEdge(dockSetting, portrait) else dock
                val paneHidden = hidePane || layoutBand == DesktopLayoutBand.Narrow
                DockDropHost(drag, dock, sidePaneWidth = 300.dp, bandHeight = 240.dp, carry = { Text("Carrying the player") }) {
                    DockedFrame(
                        dock = dock,
                        layoutBand = layoutBand,
                        portrait = portrait,
                        frost = false,
                        modifier = Modifier.fillMaxSize(),
                        rail = { Text("Rail") },
                        sidePane = if (paneHidden) null else { { DockGrip(drag, dock, { docked += it }) { Text("Now playing") } } },
                        sidePaneWidth = 300.dp,
                        onGrowSidePane = {},
                        band = if (paneHidden) null else {
                            {
                                Box(Modifier.fillMaxSize().testTag("band")) {
                                    DockGrip(drag, dock, { docked += it }, Modifier.align(Alignment.TopCenter)) { Text("Now playing") }
                                }
                            }
                        },
                        bandHeight = 240.dp,
                        onGrowBand = {},
                        notices = {},
                        footer = {},
                        queueColumn = if (queueColumn) { { Text("Queue column") } } else null,
                        queueColumnWidth = 400.dp,
                    ) {
                        if (searchField) {
                            // Like LibraryHeader's search: it eats Escape to clear itself.
                            var query by remember { mutableStateOf("abc") }
                            BasicTextField(
                                query,
                                { query = it },
                                Modifier.testTag("search").onPreviewKeyEvent {
                                    if (it.key != Key.Escape) return@onPreviewKeyEvent false
                                    if (it.type == KeyEventType.KeyDown) searchEscapes++
                                    true
                                },
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxSize().testTag("content")) {
                            tabs.SaveableStateProvider("Library") {
                                val list = rememberLazyListState()
                                LazyColumn(Modifier.fillMaxSize(), state = list) {
                                    items(200) { Text("Song ${it.toString().padStart(3, '0')}") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun releasingOnTheCurrentEdgeReturnsTheCarryToItsGrip() = runDesktopComposeUiTest(width = 1280, height = 900) {
        showFrame()
        grip().performTouchInput {
            down(center)
            moveTo(center + Offset(40f, 0f), delayMillis = 50)
        }
        waitForIdle()
        assertEquals(DockEdge.RIGHT, drag.target)
        val held = onNodeWithText("Carrying the player").getBoundsInRoot()
        mainClock.autoAdvance = false
        grip().performTouchInput { up() }
        mainClock.advanceTimeBy(208)
        val settling = onNodeWithText("Carrying the player").getBoundsInRoot()
        assertTrue(settling.left < held.left + 20.dp, "The card returns to the grip, not the zone centre: $settling vs $held")
        assertEquals(null, drag.lastRelease?.edge, "Cancellation settles at drag.anchor rather than the zone centre")
        assertEquals(emptyList(), docked)
        mainClock.advanceTimeBy(300)
        onNodeWithText("Carrying the player").assertDoesNotExist()
    }

    @Test fun responsiveLayoutChangesLeaveNoSlotGhostAfterOneFrame() = runDesktopComposeUiTest(width = 1280, height = 900) {
        dockSetting = NowPlayingDock.AUTO
        showFrame()
        mainClock.autoAdvance = false
        val beside = onNodeWithTag("content").getBoundsInRoot()
        layoutBand = DesktopLayoutBand.Narrow
        mainClock.advanceTimeByFrame()
        val narrow = onNodeWithTag("content").getBoundsInRoot()
        assertTrue(narrow.right >= beside.right + 300.dp, "Narrow content immediately takes the entire side slot: $narrow vs $beside")
        layoutBand = DesktopLayoutBand.Wide
        mainClock.advanceTimeByFrame()
        assertEquals(beside, onNodeWithTag("content").getBoundsInRoot())
        portrait = true
        mainClock.advanceTimeByFrame()
        val below = onNodeWithTag("content").getBoundsInRoot()
        assertEquals(narrow.right, below.right, "AUTO portrait has no leaving right-slot width")
        assertTrue(below.bottom <= beside.bottom - 240.dp, "The bottom slot arrives at full height in one frame")
        portrait = false
        mainClock.advanceTimeByFrame()
        assertEquals(beside, onNodeWithTag("content").getBoundsInRoot(), "AUTO landscape leaves no bottom-slot ghost")
    }

    @Test fun releasedZonesHoldTheirArrivalAndEmphasisWhileFading() = runDesktopComposeUiTest(width = 1280, height = 900) {
        showFrame()
        mainClock.autoAdvance = false
        val grip = grip().getBoundsInRoot()
        grip().performTouchInput {
            down(center)
            moveTo(Offset(-grip.left.toPx() + 640f, 450f - grip.top.toPx()), delayMillis = 50)
        }
        mainClock.advanceTimeBy(48)
        // Enter a new zone before either arrival or emphasis can finish.
        grip().performTouchInput { moveTo(Offset(-grip.left.toPx() + 30f, center.y), delayMillis = 16) }
        mainClock.advanceTimeBy(32)
        grip().performTouchInput { up() }
        mainClock.advanceTimeByFrame()
        val released = onNodeWithText("Left").getBoundsInRoot()
        mainClock.advanceTimeBy(64)
        assertEquals(released, onNodeWithText("Left").getBoundsInRoot(), "Arrival and emphasis hold during opacity fade")
        mainClock.advanceTimeBy(300)
        onNodeWithText("Left").assertDoesNotExist()
    }

    @Test fun movingThePlayerKeepsTheLibraryWhereItWasScrolled() = runDesktopComposeUiTest(width = 1280, height = 900) {
        showFrame()
        onNode(hasScrollToIndexAction()).performScrollToIndex(120)
        waitForIdle()
        onNodeWithText("Song 120").assertIsDisplayed()
        val playerAtRight = onNodeWithText("Now playing").getBoundsInRoot().left

        for (edge in listOf(DockEdge.LEFT, DockEdge.TOP, DockEdge.BOTTOM, DockEdge.RIGHT)) {
            dock = edge
            waitForIdle()
            onNodeWithText("Song 120").assertIsDisplayed()
            onNodeWithText("Song 000").assertDoesNotExist()
        }
        assertEquals(playerAtRight, onNodeWithText("Now playing").getBoundsInRoot().left)
    }

    @Test fun theLeftDockSitsBetweenTheRailAndTheContent() = runDesktopComposeUiTest(width = 1280, height = 900) {
        dock = DockEdge.LEFT
        showFrame()
        val rail = onNodeWithText("Rail").getBoundsInRoot()
        val player = onNodeWithText("Now playing").getBoundsInRoot()
        val content = onNodeWithText("Song 000").getBoundsInRoot()
        assertTrue(rail.right <= player.left && player.right <= content.left, "rail | player | content, got $rail $player $content")

        dock = DockEdge.TOP
        waitForIdle()
        val band = onNodeWithText("Now playing").getBoundsInRoot()
        assertTrue(band.bottom <= onNodeWithText("Song 000").getBoundsInRoot().top, "The top band sits above the content")
    }

    @Test fun theUltrawideQueueColumnKeepsTheLibraryScrolledThroughDockMovesAndToggles() =
        runDesktopComposeUiTest(width = 2600, height = 1000) {
            queueColumn = true
            showFrame()
            onNode(hasScrollToIndexAction()).performScrollToIndex(120)
            waitForIdle()

            for (edge in listOf(DockEdge.LEFT, DockEdge.TOP, DockEdge.BOTTOM, DockEdge.RIGHT)) {
                dock = edge
                waitForIdle()
                onNodeWithText("Song 120").assertIsDisplayed()
                onNodeWithText("Song 000").assertDoesNotExist()
                val content = onNodeWithText("Song 120").getBoundsInRoot()
                val queue = onNodeWithText("Queue column").getBoundsInRoot()
                val player = onNodeWithText("Now playing").getBoundsInRoot()
                assertTrue(content.right <= queue.left, "$edge: the queue stands right of the content, got $content $queue")
                when (edge) {
                    DockEdge.RIGHT -> assertTrue(queue.right <= player.left, "rail | content | queue | player, got $queue $player")
                    DockEdge.LEFT -> assertTrue(player.right <= content.left, "rail | player | content | queue, got $player $content")
                    // The band ends where the queue column begins.
                    else -> {
                        val band = onNodeWithTag("band").getBoundsInRoot()
                        assertTrue(band.right <= queue.left && queue.left - band.right < 20.dp, "$edge: band | queue, got $band $queue")
                    }
                }
            }

            queueColumn = false
            waitForIdle()
            onNodeWithText("Queue column").assertDoesNotExist()
            onNodeWithText("Song 120").assertIsDisplayed()
            queueColumn = true
            waitForIdle()
            onNodeWithText("Song 120").assertIsDisplayed()
        }

    private fun ComposeUiTest.grip() = onNodeWithContentDescription("Drag to move the player to another edge")

    @Test fun movingThePlayerNeverComposesItTwice() = runDesktopComposeUiTest(width = 1280, height = 900) {
        showFrame()
        mainClock.autoAdvance = false
        for (edge in listOf(DockEdge.LEFT, DockEdge.TOP, DockEdge.RIGHT)) {
            dock = edge
            // Sample the slide while both the leaving and the arriving slot are on screen.
            for (step in 1..6) {
                mainClock.advanceTimeBy(48)
                onAllNodesWithText("Now playing").assertCountEquals(1)
            }
            mainClock.advanceTimeBy(1_000)
            onAllNodesWithText("Now playing").assertCountEquals(1)
        }
    }

    @Test fun theSlotsTradeWidthWhileThePlayerSlidesSoTheContentNeverGaps() = runDesktopComposeUiTest(width = 1280, height = 900) {
        dockSetting = NowPlayingDock.AUTO
        showFrame()
        mainClock.autoAdvance = false
        val before = onNodeWithTag("content").getBoundsInRoot()
        val width = before.right - before.left
        dockSetting = NowPlayingDock.LEFT
        dock = DockEdge.LEFT
        var lastLeft = before.left
        var sampledMidway = false
        // 240-280 ms slide, sampled every 70 ms: the content column keeps its width while it moves right.
        for (step in 1..5) {
            mainClock.advanceTimeBy(70)
            val content = onNodeWithTag("content").getBoundsInRoot()
            val now = content.right - content.left
            assertTrue(now.value in width.value - 1f..width.value + 1f, "content width held at $content vs $before")
            assertTrue(content.left >= lastLeft, "content only moves right: $content after $lastLeft")
            if (content.left > before.left + 20.dp && content.left < before.left + 280.dp) sampledMidway = true
            lastLeft = content.left
        }
        assertTrue(sampledMidway, "a sample landed mid-slide")
        mainClock.advanceTimeBy(1_000)
        val after = onNodeWithTag("content").getBoundsInRoot()
        assertEquals(width, after.right - after.left)
        assertTrue(after.left > before.left + 290.dp, "the whole pane now sits on the left: $after")
    }

    @Test fun hidingThePlayerFoldsTheSlotAwayAndShowingItBringsItBack() = runDesktopComposeUiTest(width = 1280, height = 900) {
        showFrame()
        val shown = onNodeWithText("Now playing").getBoundsInRoot()
        val contentBeside = onNodeWithTag("content").getBoundsInRoot()
        hidePane = true
        waitForIdle()
        onNodeWithText("Now playing").assertDoesNotExist()
        assertTrue(onNodeWithTag("content").getBoundsInRoot().right > shown.left, "The content took the pane's room")
        hidePane = false
        waitForIdle()
        assertEquals(shown, onNodeWithText("Now playing").getBoundsInRoot())
        assertEquals(contentBeside, onNodeWithTag("content").getBoundsInRoot())
    }

    @Test fun theCarriedCardFollowsTheDragAndIsGoneOnceACancelSettles() = runDesktopComposeUiTest(width = 1280, height = 900) {
        showFrame()
        onNodeWithText("Carrying the player").assertDoesNotExist()
        val grip = grip().getBoundsInRoot()
        grip().performTouchInput {
            down(center)
            moveTo(Offset(-grip.left.toPx() + 30f, center.y), delayMillis = 50)
        }
        waitForIdle()
        onNodeWithText("Carrying the player").assertIsDisplayed()
        assertTrue(drag.cancel())
        waitForIdle()
        onNodeWithText("Drag to an edge to move the player. Esc cancels.").assertDoesNotExist()
        onNodeWithText("Let go to put the player on the left").assertDoesNotExist()
        onNodeWithText("Carrying the player").assertDoesNotExist()
        grip().performTouchInput { up() }
        waitForIdle()
        assertEquals(emptyList(), docked)
    }

    @Test fun draggingTheGripToAnEdgeDocksThere() = runDesktopComposeUiTest(width = 1280, height = 900) {
        showFrame()
        val grip = grip().getBoundsInRoot()
        grip().performTouchInput {
            down(center)
            // Toward the window's left edge, in the grip's own coordinates.
            moveTo(Offset(-grip.left.toPx() + 30f, center.y), delayMillis = 50)
        }
        waitForIdle()
        onNodeWithText("Let go to put the player on the left").assertIsDisplayed()
        onNodeWithText("Player is here").assertIsDisplayed()
        grip().performTouchInput { up() }
        waitForIdle()
        assertEquals(listOf(DockEdge.LEFT), docked)
        onNodeWithText("Player is here").assertDoesNotExist()
    }

    @Test fun lettingGoInTheMiddleOrOnItsOwnEdgeChangesNothing() = runDesktopComposeUiTest(width = 1280, height = 900) {
        showFrame()
        val grip = grip().getBoundsInRoot()
        grip().performTouchInput {
            down(center)
            moveTo(Offset(-grip.left.toPx() + 640f, 450f - grip.top.toPx()), delayMillis = 50)
        }
        waitForIdle()
        onNodeWithText("Drag to an edge to move the player. Esc cancels.").assertIsDisplayed()
        grip().performTouchInput { up() }

        grip().performTouchInput {
            down(center)
            moveTo(center + Offset(40f, 0f), delayMillis = 50)
        }
        waitForIdle()
        onNodeWithText("Let go to keep the player on the right").assertIsDisplayed()
        grip().performTouchInput { up() }
        waitForIdle()
        assertEquals(emptyList(), docked)
    }

    @Test fun escapeCancelsADragEvenWhileLibrarySearchHasFocus() = runDesktopComposeUiTest(width = 1280, height = 900) {
        showFrame(searchField = true)
        onNodeWithTag("search").requestFocus()
        waitForIdle()
        val grip = grip().getBoundsInRoot()
        grip().performTouchInput {
            down(center)
            moveTo(Offset(-grip.left.toPx() + 30f, center.y), delayMillis = 50)
        }
        waitForIdle()
        onNodeWithText("Player is here").assertIsDisplayed()
        onNodeWithTag("search").performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        onNodeWithText("Player is here").assertDoesNotExist()
        assertEquals(0, searchEscapes, "The drag takes Escape before the focused search field")
        grip().performTouchInput {
            moveBy(Offset(5f, 0f))
            up()
        }
        waitForIdle()
        assertEquals(emptyList(), docked)

        onNodeWithTag("search").performKeyInput { pressKey(Key.Escape) }
        assertEquals(1, searchEscapes, "With no drag, Escape still reaches the search field")
    }
}
