package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.components.SourceKind
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens

/** Recents as a ledger that fills the width: [columns] rows side by side, each with its source rail. */
@Composable
internal fun ContinueListeningSection(
    tracks: List<Track>,
    columns: Int,
    onPlayTracks: (List<Track>, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tracks.isEmpty()) return
    Column(modifier) {
        SectionRule("Continue listening", Modifier.padding(bottom = 4.dp), accent = Accents.home) {
            Text(
                "${tracks.size} recent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        tracks.chunked(columns).forEach { rowTracks ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                rowTracks.forEach { track ->
                    LedgerRow(
                        track = track,
                        onClick = { onPlayTracks(tracks, tracks.indexOf(track)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowTracks.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LedgerRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playable = track.playableSources().firstOrNull()
    val railColor = playable?.provider?.let { LocalKainosPalette.current.accent(SourceKind.of(it).slot, AccentUse.Mark) }
        ?: MaterialTheme.colorScheme.outlineVariant
    val rounding = LocalSkinTokens.current.rounding
    val sourceLabel = ledgerSourceLabel(track)
    val artistLine = ledgerArtistLine(track)

    Row(
        modifier
            .height(IntrinsicSize.Min)
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
            shape = RoundedCornerShape(rounding.normal),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                Modifier.padding(top = 6.dp, bottom = 6.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    track.artwork,
                    track.title,
                    Modifier.size(44.dp),
                    track.title,
                    shape = RoundedCornerShape(rounding.small),
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
