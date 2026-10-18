package com.universalmusic.player.ui.screens

import androidx.compose.ui.graphics.Color
import com.universalmusic.player.ui.components.AlbumLightCover
import com.universalmusic.player.ui.theme.LocalSkinTokens
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.ui.components.ArtworkImage


@Composable
internal fun ColumnScope.NowPlayingArtwork(
    title: String?,
    artwork: Artwork?,
    artMax: Dp,
    expand: Boolean,
    compact: Boolean,
    light: Color,
    onPositioned: (Rect) -> Unit,
) {
    if (expand) {
        // Takes at most the height the controls leave, so they never fall below the first screen.
        // `fill = false` lets the box shrink to the cover, so the cover sits under the header and
        // any spare height falls below the controls instead of above the art.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .heightIn(min = 120.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            val widthShare = if (compact) maxWidth else maxWidth * 0.92f
            NowPlayingArtworkFrame(
                title = title,
                artwork = artwork,
                light = light,
                modifier = Modifier
                    .size(minOf(widthShare, maxHeight, artMax))
                    .onGloballyPositioned { onPositioned(it.boundsInRoot()) },
            )
        }
    } else {
        NowPlayingArtworkFrame(
            title = title,
            artwork = artwork,
            light = light,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = artMax)
                .fillMaxWidth()
                .aspectRatio(1f)
                .onGloballyPositioned { onPositioned(it.boundsInRoot()) },
        )
    }
}

@Composable
private fun NowPlayingArtworkFrame(
    title: String?,
    artwork: Artwork?,
    light: Color,
    modifier: Modifier,
) {
    val corner = RoundedCornerShape(LocalSkinTokens.current.rounding.large)
    AlbumLightCover(light, modifier) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = corner,
            tonalElevation = 0.dp,
            shadowElevation = 3.dp,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
        ) {
            ArtworkImage(
                artwork = artwork,
                contentDescription = title ?: "Artwork",
                modifier = Modifier.fillMaxSize(),
                seed = title ?: "U",
                shape = corner,
            )
        }
    }
}
