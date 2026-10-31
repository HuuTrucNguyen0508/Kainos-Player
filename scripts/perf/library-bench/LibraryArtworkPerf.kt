package com.universalmusic.player.ui.screens

import coil3.SingletonImageLoader
import coil3.PlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.ui.components.warmQueueArtwork
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LibraryArtworkPerf {
    @Test fun neighbourDecode() = runBlocking {
        val dir = Files.createTempDirectory("library-prefetch-art-")
        val context = PlatformContext.INSTANCE
        try {
            repeat(13) { run ->
                for (warm in if(run % 2 == 0) listOf(false,true) else listOf(true,false)) {
                    val file = dir.resolve("$run-$warm.png").toFile()
                    ImageIO.write(BufferedImage(1024,1024,BufferedImage.TYPE_INT_RGB).apply {
                        for(y in 0 until height) for(x in 0 until width) setRGB(x,y, (x*7919+y*6271) or 0xff000000.toInt())
                    }, "png", file)
                    val art = Artwork(file.toURI().toString())
                    if(warm) warmQueueArtwork(context, art)
                    val start = System.nanoTime()
                    val result = SingletonImageLoader.get(context).execute(ImageRequest.Builder(context).data(art.url).size(512).build())
                    val ms = (System.nanoTime()-start)/1e6
                    assertIs<SuccessResult>(result)
                    assertTrue(result.image.width > 0)
                    if(run>=3) println("LIBRARY artworkDecode:${if(warm) "prefetched" else "cold"}\t1024\t${run-3}\t$ms\t${result.image.width}")
                }
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
