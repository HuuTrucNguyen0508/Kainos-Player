package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.universalmusic.player.ui.TestTheme
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class SelectableCopiesTest {
    private val rows = listOf("a.flac", "b.flac", "c.flac").map { CopyRow(it, "Download $it", 0L) }

    @Test fun tickedRowsNameTheButtonsAndAreTheOnlyOnesActedOn() = runComposeUiTest {
        val transferred = mutableListOf<Set<String>>()
        setContent {
            TestTheme {
                Column {
                    SelectableCopies(rows, enabled = true, shownAtFirst = 8, actions = listOf(
                        CopyAction<String>("Transfer", primary = true) { transferred += it },
                        CopyAction<String>("Skip") {},
                    ))
                }
            }
        }
        onNodeWithText("Transfer all").assertExists()
        onNodeWithText("Download a.flac").performClick()
        onNodeWithText("Download c.flac").performClick()
        onNodeWithText("2 of 3 selected").assertExists()
        onNodeWithText("Skip (2)").assertExists()
        onNodeWithText("Transfer (2)").performClick()

        assertEquals(listOf(setOf("a.flac", "c.flac")), transferred)
        onNodeWithText("Transfer all").assertExists()
    }

    @Test fun selectAllTicksEveryRowAndNothingTickedMeansAll() = runComposeUiTest {
        val acted = mutableListOf<Set<String>>()
        setContent {
            TestTheme {
                Column {
                    SelectableCopies(rows, enabled = true, shownAtFirst = 2, actions = listOf(CopyAction<String>("Skip") { acted += it }))
                }
            }
        }
        onNodeWithText("Show 1 more").assertExists()
        onNodeWithText("Skip all").performClick()
        onNodeWithText("Select all").performClick()
        onNodeWithText("3 of 3 selected").assertExists()
        onNodeWithText("Skip (3)").performClick()

        val all = setOf("a.flac", "b.flac", "c.flac")
        assertEquals(listOf(all, all), acted)
    }

    @Test fun aRowThatLeavesTheListIsNoLongerTicked() = runComposeUiTest {
        var shown by mutableStateOf(rows)
        setContent {
            TestTheme {
                Column { SelectableCopies(shown, enabled = true, shownAtFirst = 8, actions = listOf(CopyAction<String>("Skip") {})) }
            }
        }
        onNodeWithText("Download a.flac").performClick()
        onNodeWithText("Download b.flac").performClick()
        shown = rows.drop(1)
        waitForIdle()
        onNodeWithText("1 of 2 selected").assertExists()
    }
}
