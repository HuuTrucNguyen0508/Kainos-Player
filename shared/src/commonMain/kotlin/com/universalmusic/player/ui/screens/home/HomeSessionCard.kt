package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.ArtworkImage

@Composable
internal fun SessionCard(
    focus: SessionFocus,
    onOpenNowPlaying: () -> Unit,
    onResume: () -> Unit,
    onPlayLast: (Track, List<Track>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = when (focus) {
        is SessionFocus.Restored -> focus.track
        is SessionFocus.LastPlayed -> focus.track
    }
    val sourceLabel = sessionSourceLabel(track)
    val eyebrow = sessionEyebrow(focus)
    val onCardClick: () -> Unit = when (focus) {
        is SessionFocus.Restored -> onOpenNowPlaying
        is SessionFocus.LastPlayed -> {
            { onPlayLast(focus.track, focus.queue) }
        }
    }
    val onPlayClick: () -> Unit = when (focus) {
        is SessionFocus.Restored -> {
            {
                if (focus.isPlaying) {
                    onOpenNowPlaying()
                } else {
                    onResume()
                }
            }
        }
        is SessionFocus.LastPlayed -> {
            { onPlayLast(focus.track, focus.queue) }
        }
    }

    BoxWithConstraints(modifier) {
        val artSize = if (maxWidth >= 600.dp) 88.dp else 72.dp
        Surface(
            onClick = onCardClick,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    track.artwork,
                    track.title,
                    Modifier.size(artSize),
                    track.title,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artistLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$eyebrow · $sourceLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                FilledIconButton(onClick = onPlayClick, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = when (focus) {
                            is SessionFocus.Restored -> if (focus.isPlaying) "Open now playing" else "Resume"
                            is SessionFocus.LastPlayed -> "Play"
                        },
                    )
                }
            }
        }
    }
}
