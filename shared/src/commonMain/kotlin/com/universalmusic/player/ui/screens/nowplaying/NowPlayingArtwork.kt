package com.universalmusic.player.ui.screens

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

internal val NowPlayingArtCorner = RoundedCornerShape(20.dp)

@Composable
internal fun ColumnScope.NowPlayingArtwork(
    title: String?,
    artwork: Artwork?,
    artMax: Dp,
    expand: Boolean,
    compact: Boolean,
    onPositioned: (Rect) -> Unit,
) {
    if (expand) {
        // Takes whatever height the controls leave, so they never fall below the first screen.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .heightIn(min = 120.dp),
            // Spare height goes above the cover (album light fills it) so the title hugs the art.
            contentAlignment = Alignment.BottomCenter,
        ) {
            val widthShare = if (compact) maxWidth else maxWidth * 0.92f
            NowPlayingArtworkFrame(
                title = title,
                artwork = artwork,
                modifier = Modifier
                    .size(minOf(widthShare, maxHeight, artMax))
                    .onGloballyPositioned { onPositioned(it.boundsInRoot()) },
            )
        }
    } else {
        NowPlayingArtworkFrame(
            title = title,
            artwork = artwork,
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
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = NowPlayingArtCorner,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        ArtworkImage(
            artwork = artwork,
            contentDescription = title ?: "Artwork",
            modifier = Modifier.fillMaxSize(),
            seed = title ?: "U",
            shape = NowPlayingArtCorner,
        )
    }
}
