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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class DockedFrameUiTest {
    private var dock by mutableStateOf(DockEdge.RIGHT)
    private val drag = DockDragState()
    private val docked = mutableListOf<DockEdge>()

    /** The app's frame with a saved, scrollable screen in the content slot, as Library sits in AppScaffold. */
    private fun ComposeUiTest.showFrame() {
        setContent {
            TestTheme {
                val tabs = rememberSaveableStateHolder()
                DockDropHost(drag, dock, sidePaneWidth = 300.dp, bandHeight = 240.dp) {
                    DockedFrame(
                        dock = dock,
                        frost = false,
                        modifier = Modifier.fillMaxSize(),
                        rail = { Text("Rail") },
                        sidePane = { DockGrip(drag, dock, { docked += it }) { Text("Now playing") } },
                        sidePaneWidth = 300.dp,
                        onGrowSidePane = {},
                        band = {
                            Box(Modifier.fillMaxSize()) {
                                DockGrip(drag, dock, { docked += it }, Modifier.align(Alignment.TopCenter)) { Text("Now playing") }
                            }
                        },
                        bandHeight = 240.dp,
                        onGrowBand = {},
                        notices = {},
                        footer = {},
                    ) {
                        Box(Modifier.weight(1f).fillMaxSize()) {
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

    private fun ComposeUiTest.grip() = onNodeWithContentDescription("Drag to move the player to another edge")

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

    @Test fun escapeCancelsADragInProgress() = runDesktopComposeUiTest(width = 1280, height = 900) {
        showFrame()
        val grip = grip().getBoundsInRoot()
        grip().performTouchInput {
            down(center)
            moveTo(Offset(-grip.left.toPx() + 30f, center.y), delayMillis = 50)
        }
        waitForIdle()
        // AppScaffold's back handling (Escape) calls this before anything else.
        assertTrue(drag.cancel())
        waitForIdle()
        onNodeWithText("Player is here").assertDoesNotExist()
        grip().performTouchInput {
            moveBy(Offset(5f, 0f))
            up()
        }
        waitForIdle()
        assertEquals(emptyList(), docked)
    }
}
