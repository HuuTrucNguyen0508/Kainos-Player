package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import com.universalmusic.player.domain.model.*
import com.universalmusic.player.ui.components.TrackRow
import com.universalmusic.player.ui.theme.*
import com.universalmusic.player.data.settings.FolioTint
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlin.test.*

/** Real Compose/Skia rows and Coil file artwork; CPU headless rendering, no display or audio. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LibraryRenderPerf {
    @Test fun scrollFrames() {
        val directory = Files.createTempDirectory("kainos-library-art-")
        val art = directory.resolve("cover.png").toFile()
        ImageIO.write(BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB).apply {
            createGraphics().apply { color = java.awt.Color.BLUE; fillRect(0, 0, 96, 96); dispose() }
        }, "png", art)
        try {
            for (size in listOf(2_000, 20_000, 100_000)) {
                val tracks = syntheticLibrary(size).map { it.copy(artwork = Artwork(art.toURI().toString())) }
                val scheduler = TestCoroutineScheduler()
                val scope = TestScope(StandardTestDispatcher(scheduler))
                val list = LazyListState()
                var compositions = 0
                val scene = ImageComposeScene(width = 900, height = 700, density = Density(1f), coroutineContext = scope.coroutineContext) {
                    KainosTheme(folioPalette(FolioTint.FOLIO, false), skinTokens(Skin.FOLIO)) {
                        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
                            items(tracks, key = { it.canonicalId }) { track ->
                                SideEffect { compositions++ }
                                TrackRow(track = track, onClick = {})
                            }
                        }
                    }
                }
                try {
                    repeat(10) { frame -> scene.render(frame * 16_666_667L).close(); scheduler.runCurrent() }
                    repeat(10) { run ->
                        val before = compositions
                        val start = System.nanoTime()
                        repeat(10) { step ->
                            scope.launch { list.scrollToItem(1 + (run * 10 + step) * 10) }
                            scheduler.runCurrent()
                            scene.render((10 + run * 10 + step) * 16_666_667L).close()
                            scheduler.runCurrent()
                        }
                        assertTrue(list.firstVisibleItemIndex > 0)
                        println("LIBRARY scroll10frames\t$size\t$run\t${(System.nanoTime()-start)/1e6}\t${compositions-before}")
                    }
                    // Prove Coil decoded the generated artwork rather than measuring an error tile.
                    scene.render(199 * 16_666_667L).use { image ->
                        image.encodeToData().use { data ->
                            val png = ImageIO.read(checkNotNull(data).bytes.inputStream())
                            val pixels = png.getRGB(0, 0, png.width, png.height, null, 0, png.width)
                            assertTrue(pixels.count { it == 0xff0000ff.toInt() } > 100, "Coil artwork did not render")
                        }
                    }
                    val before = compositions
                    repeat(60) { frame -> scene.render((200 + frame) * 16_666_667L).close(); scheduler.runCurrent() }
                    println("RECOMPOSE idleRows size=$size perSimulatedSecond=${compositions-before}")
                } finally { scene.close() }
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
