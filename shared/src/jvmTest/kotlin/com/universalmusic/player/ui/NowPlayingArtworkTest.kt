package com.universalmusic.player.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil3.EventListener
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.Precision
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.ui.screens.NowPlayingArtwork
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class NowPlayingArtworkTest {
    @Test
    fun coverDoesNotUpscaleAListThumbnailFromSharedCache() = runBlocking {
        val file = Files.createTempFile("kainos-cover", ".png").toFile()
        ImageIO.write(BufferedImage(640, 640, BufferedImage.TYPE_INT_RGB), "png", file)
        val context = PlatformContext.INSTANCE
        val results = CopyOnWriteArrayList<SuccessResult>()
        val loader = ImageLoader.Builder(context).eventListener(object : EventListener() {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) { results.add(result) }
        }).build()
        val previous = SingletonImageLoader.get(context)
        SingletonImageLoader.setUnsafe(loader)
        val scene = ImageComposeScene(500, 500)
        try {
            val url = file.toURI().toString()
            val thumbnail = loader.execute(ImageRequest.Builder(context).data(url).size(48)
                .precision(Precision.INEXACT).build()) as SuccessResult
            assertTrue(thumbnail.image.width < 500)
            // An unsampled cache entry is accepted at any display size by INEXACT requests.
            loader.memoryCache!![thumbnail.memoryCacheKey!!] = MemoryCache.Value(thumbnail.image)
            results.clear()
            scene.setContent {
                Column(Modifier.fillMaxSize()) {
                    NowPlayingArtwork("Cover", Artwork(url), 500.dp, false, false, Color.Red) {}
                }
            }
            val deadline = System.nanoTime() + 5_000_000_000L
            while (results.isEmpty() && System.nanoTime() < deadline) {
                scene.render(0).close()
                kotlinx.coroutines.delay(10)
            }
            assertTrue(results.isNotEmpty(), "Cover request completed")
            val result = results.last()
            assertTrue(result.image.width >= 500,
                "Cover decoded ${result.image.width}px for 500px bounds; source=${result.dataSource}, sampled=${result.isSampled}, precision=${result.request.precision}")
        } finally {
            scene.close()
            SingletonImageLoader.setUnsafe(previous)
            loader.shutdown()
            file.delete()
        }
    }
}
