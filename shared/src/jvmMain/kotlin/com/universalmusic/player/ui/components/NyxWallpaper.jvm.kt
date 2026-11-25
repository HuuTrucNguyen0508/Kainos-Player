package com.universalmusic.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import kotlin.math.abs
import kotlin.math.ln

private val skySizes = listOf(IntSize(688, 288), IntSize(512, 288), IntSize(384, 216), IntSize(288, 512))

internal fun nyxSkySize(windowSize: IntSize, cellPx: Int): IntSize? {
    if (windowSize.width <= 0 || windowSize.height <= 0) return null
    val aspect = windowSize.width.toDouble() / windowSize.height
    val columns = windowSize.width.toDouble() / cellPx
    return skySizes.minWith(compareBy(
        { abs(ln(it.width.toDouble() / it.height / aspect)) },
        { abs(ln(it.width / columns)) },
    ))
}

private object NyxSkyResources

@Composable
internal actual fun rememberNyxSkyBitmap(windowSize: IntSize, cellPx: Int): ImageBitmap? {
    val art = nyxSkySize(windowSize, cellPx)
    val bitmap by produceState<ImageBitmap?>(null, art) {
        if (art != null) {
            value = withContext(Dispatchers.IO) {
                val path = "/nyx/nyx-art-${art.width}x${art.height}.png"
                NyxSkyResources::class.java.getResourceAsStream(path)?.use { stream ->
                    Image.makeFromEncoded(stream.readBytes()).use { it.toComposeImageBitmap() }
                }
            }
        }
    }
    return bitmap
}
