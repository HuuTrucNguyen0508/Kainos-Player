package com.universalmusic.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

@Composable
internal actual fun rememberNyxWallpaperBitmap(path: String?): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, path) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val file = Path.of(path)
                if (!Files.isRegularFile(file)) return@runCatching null
                Image.makeFromEncoded(Files.readAllBytes(file)).use { it.toComposeImageBitmap() }
            }.getOrNull()
        }
    }
    return bitmap
}
