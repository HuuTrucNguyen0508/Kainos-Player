package com.universalmusic.player.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingPaneStateTest {
    @Test
    fun compactToggleResetsManualPaneChoiceInBothDirections() {
        val scheduler = TestCoroutineScheduler()
        val compact = mutableStateOf(false)
        lateinit var expanded: MutableState<Boolean>
        val scene = ImageComposeScene(100, 100, coroutineContext = StandardTestDispatcher(scheduler)) {
            expanded = rememberNowPlayingPaneExpanded(compact.value, desktop = true)
        }
        fun recompose() {
            scene.render().close()
            scheduler.runCurrent()
            scene.render().close()
        }
        try {
            recompose()
            assertTrue(expanded.value, "Full mode starts with the pane open")
            compact.value = true
            recompose()
            assertFalse(expanded.value, "Compact ON hides the pane")
            compact.value = false
            recompose()
            assertTrue(expanded.value, "Compact OFF restores the pane")

            expanded.value = false
            recompose()
            assertFalse(expanded.value, "Manual hide survives recomposition")
            compact.value = true
            recompose()
            assertFalse(expanded.value)
            expanded.value = true
            recompose()
            assertTrue(expanded.value, "The rail arrow can open the pane in compact mode")
            compact.value = false
            recompose()
            assertTrue(expanded.value)
            compact.value = true
            recompose()
            assertFalse(expanded.value, "A new compact preference clears the manual override")
        } finally {
            scene.close()
        }
    }

    @Test
    fun persistedCompactPreferenceSetsInitialPaneState() {
        for (compact in listOf(false, true)) {
            val scheduler = TestCoroutineScheduler()
            lateinit var expanded: MutableState<Boolean>
            val scene = ImageComposeScene(100, 100, coroutineContext = StandardTestDispatcher(scheduler)) {
                expanded = rememberNowPlayingPaneExpanded(compact, desktop = true)
            }
            try {
                scene.render().close()
                scheduler.runCurrent()
                scene.render().close()
                if (compact) assertFalse(expanded.value) else assertTrue(expanded.value)
            } finally {
                scene.close()
            }
        }
    }
}
