package com.universalmusic.player.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.NowPlayingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Real composition, 4Hz engine positions. Counts the scaffold observation scope, not GPU time. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryPlaybackPerf {
    @Test fun positionInvalidations() {
        for (narrow in listOf(false, true)) repeat(10) { run ->
            val state = MutableStateFlow(NowPlayingState(track = Track("local:1", "Track", emptyList()), isPlaying = true))
            val scheduler = TestCoroutineScheduler()
            val scope = TestScope(StandardTestDispatcher(scheduler))
            var root = 0
            var progress = 0
            val scene = ImageComposeScene(width = 400, height = 100, coroutineContext = scope.coroutineContext) {
                val track = if (narrow) playbackChromeTrack(state).value else state.collectAsState().value.track
                SideEffect { root++ }
                Text(checkNotNull(track).title)
                Progress(state) { progress++ }
            }
            try {
                repeat(4) { scene.render(it.toLong()).close(); scheduler.runCurrent() }
                val beforeRoot = root
                val beforeProgress = progress
                repeat(4) { tick ->
                    state.value = state.value.copy(positionMs = (tick + 1) * 250L)
                    Snapshot.sendApplyNotifications()
                    scheduler.runCurrent()
                    repeat(4) { frame -> scene.render((tick + 1) * 250_000_000L + frame).close(); scheduler.runCurrent() }
                }
                assertEquals(4, progress - beforeProgress)
                assertEquals(if (narrow) 0 else 4, root - beforeRoot)
                println("LIBRARY chromeRecompositions:${if(narrow) "trackFlow" else "wholeState"}\t1\t$run\t${root-beforeRoot}\t${progress-beforeProgress}")
            } finally { scene.close() }
        }
    }
}

@Composable private fun Progress(state: MutableStateFlow<NowPlayingState>, count: () -> Unit) {
    val now by state.collectAsState()
    SideEffect(count)
    Text(now.positionMs.toString())
}
