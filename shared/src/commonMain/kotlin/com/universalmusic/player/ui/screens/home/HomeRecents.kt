package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.theme.providerColor

@Composable
internal fun ContinueListeningSection(
    tracks: List<Track>,
    onPlayTracks: (List<Track>, Int) -> Unit,
) {
    if (tracks.isEmpty()) return
    Text(
        "Continue listening",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
    )
    tracks.forEachIndexed { index, track ->
        LedgerRow(
            track = track,
            onClick = { onPlayTracks(tracks, index) },
        )
    }
}

@Composable
private fun LedgerRow(
    track: Track,
    onClick: () -> Unit,
) {
    val playable = track.playableSources().firstOrNull()
    val railColor = playable?.provider?.let { providerColor(it.displayName) }
        ?: MaterialTheme.colorScheme.outlineVariant
    val sourceLabel = ledgerSourceLabel(track)
    val artistLine = ledgerArtistLine(track)

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 20.dp)
            .semantics { contentDescription = "${track.title}. $artistLine. $sourceLabel" },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(railColor),
        )
        Spacer(Modifier.width(14.dp))
        Surface(
            onClick = onClick,
            color = Color.Transparent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                Modifier.padding(top = 6.dp, bottom = 6.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    track.artwork,
                    track.title,
                    Modifier.size(44.dp),
                    track.title,
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artistLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                track.durationMs?.takeIf { it > 0 }?.let { ms ->
                    Text(
                        formatTime(ms),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
