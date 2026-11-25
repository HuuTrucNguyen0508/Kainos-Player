package com.universalmusic.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap

/** Android does not bundle the desktop nebula wallpaper; Nyx falls back to flat void and grid. */
@Composable
internal actual fun rememberNyxWallpaperBitmap(path: String?): ImageBitmap? = null
