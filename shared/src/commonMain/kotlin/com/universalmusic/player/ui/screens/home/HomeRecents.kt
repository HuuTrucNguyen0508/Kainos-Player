package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
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
import com.universalmusic.player.ui.theme.AccentSlot
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
    trackActions: TrackActionsPresenter? = null,
) {
    LedgerShelf(
        title = "Continue listening",
        caption = "${tracks.size} recent",
        accent = Accents.home,
        rows = tracks.map { LedgerEntry(it) },
        columns = columns,
        onPlayTracks = onPlayTracks,
        modifier = modifier,
        trackActions = trackActions,
    )
}

/** One of Home's history shelves; tapping a row plays the shelf from that row. */
@Composable
internal fun HistoryShelf(
    shelf: HomeShelf,
    columns: Int,
    onPlayTracks: (List<Track>, Int) -> Unit,
    modifier: Modifier = Modifier,
    trackActions: TrackActionsPresenter? = null,
) {
    val (title, caption, accent) = when (shelf.kind) {
        ShelfKind.ON_REPEAT -> Triple("On repeat", "Most played in the last 30 days", Accents.repeat)
        ShelfKind.REDISCOVER -> Triple("Rediscover", "Hearted, last played over two months ago", Accents.heart)
        ShelfKind.RECENTLY_ADDED -> Triple("Recently added", "Newest files in your music folders", Accents.local)
    }
    LedgerShelf(
        title = title,
        caption = caption,
        accent = accent,
        rows = shelf.rows.take(shelfRowLimit(columns)).map {
            LedgerEntry(it.track, it.note, emphasized = shelf.kind == ShelfKind.ON_REPEAT)
        },
        columns = columns,
        onPlayTracks = onPlayTracks,
        modifier = modifier,
        trackActions = trackActions,
    )
}

/** [note] replaces the duration when set; [emphasized] draws it in the shelf's accent. */
private data class LedgerEntry(val track: Track, val note: String? = null, val emphasized: Boolean = false)

@Composable
private fun LedgerShelf(
    title: String,
    caption: String,
    accent: AccentSlot,
    rows: List<LedgerEntry>,
    columns: Int,
    onPlayTracks: (List<Track>, Int) -> Unit,
    modifier: Modifier = Modifier,
    trackActions: TrackActionsPresenter? = null,
) {
    if (rows.isEmpty()) return
    val tracks = rows.map { it.track }
    val noteColor = LocalKainosPalette.current.accent(accent, AccentUse.Mark)
    Column(modifier) {
        SectionRule(title, Modifier.padding(bottom = 4.dp), accent = accent) {
            Text(
                caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        rows.withIndex().chunked(columns).forEach { line ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                line.forEach { (index, entry) ->
                    LedgerRow(
                        track = entry.track,
                        onClick = { onPlayTracks(tracks, index) },
                        modifier = Modifier.weight(1f),
                        trackActions = trackActions,
                        note = entry.note,
                        noteColor = if (entry.emphasized) noteColor else null,
                    )
                }
                repeat(columns - line.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LedgerRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trackActions: TrackActionsPresenter? = null,
    note: String? = null,
    noteColor: Color? = null,
) {
    var menuOpen by remember(track.canonicalId) { mutableStateOf(false) }
    val openMenu: (() -> Unit)? = trackActions?.let { { menuOpen = true } }
    val playable = track.playableSources().firstOrNull()
    val railColor = playable?.provider?.let { LocalKainosPalette.current.accent(SourceKind.of(it).slot, AccentUse.Mark) }
        ?: MaterialTheme.colorScheme.outlineVariant
    val rounding = LocalSkinTokens.current.rounding
    val sourceLabel = ledgerSourceLabel(track)
    val artistLine = ledgerArtistLine(track)

    Row(
        modifier
            .height(IntrinsicSize.Min)
            .semantics {
                contentDescription = listOfNotNull(track.title, artistLine, note, sourceLabel).joinToString(". ")
            },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(railColor),
        )
        Spacer(Modifier.width(14.dp))
        Surface(
            color = Color.Transparent,
            shape = RoundedCornerShape(rounding.normal),
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(rounding.normal))
                .then(if (openMenu != null) Modifier.onSecondaryClick(openMenu) else Modifier)
                .combinedClickable(onLongClick = openMenu, onClick = onClick),
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
                val trailing = note ?: track.durationMs?.takeIf { it > 0 }?.let(::formatTime)
                if (trailing != null) {
                    Text(
                        trailing,
                        style = if (noteColor != null) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelSmall,
                        color = noteColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (trackActions != null) {
                    TrackActionsMenuButton(track, trackActions, menuOpen, { menuOpen = it })
                }
            }
        }
    }
}
