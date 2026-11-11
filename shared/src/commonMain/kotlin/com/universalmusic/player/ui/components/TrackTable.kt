package com.universalmusic.player.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track

/** A track list at least this wide lays its rows out as a table; narrower lists keep stacked rows. */
val TrackTableMinWidth: Dp = 1100.dp

internal val TrackTableCoverSize: Dp = 40.dp
internal val TrackTableAvailabilityWidth: Dp = 150.dp
internal val TrackTableSourceWidth: Dp = 84.dp

/**
 * The columns of one wide track list, worked out once from the list's width. Every row of the list
 * reads the same value, so a column starts at the same x on every row whatever its trailing holds.
 */
@Immutable
data class TrackTableColumns(
    val titleWidth: Dp,
    val artistWidth: Dp,
    /** Null when the list leaves the Album column out. */
    val albumWidth: Dp?,
    /** Fixed width of the trailing slot (heart, time, menu); content sits at its end. */
    val trailingWidth: Dp,
    /** How far the time's right edge sits in from the trailing slot's end; null when the list shows no time. */
    val timeEndInset: Dp? = null,
) {
    val showAlbum: Boolean get() = albumWidth != null
}

/** Text columns stop growing here; past it the slack opens before the marks, which stay at the right edge. */
internal val TrackTableTitleMaxWidth: Dp = 640.dp
internal val TrackTableDetailMaxWidth: Dp = 400.dp

/** Row and list padding (8dp each side, twice) and the 12dp gap between each of a row's cells. */
private val TrackTableRowInsets: Dp = 32.dp
private val TrackTableCellGap: Dp = 12.dp

/** The table for a list [listWidth] wide, or null when its rows should stay stacked. */
fun trackTableColumns(
    listWidth: Dp,
    showAlbum: Boolean,
    trailingWidth: Dp,
    timeEndInset: Dp? = null,
): TrackTableColumns? {
    if (listWidth < TrackTableMinWidth) return null
    // Cover, title, artist, album?, slack, availability, source, trailing.
    val cells = if (showAlbum) 8 else 7
    val fixed = TrackTableRowInsets + TrackTableCoverSize + TrackTableAvailabilityWidth + TrackTableSourceWidth +
        trailingWidth + TrackTableCellGap * (cells - 1)
    val text = (listWidth - fixed).coerceAtLeast(0.dp)
    return TrackTableColumns(
        titleWidth = minOf(text * (if (showAlbum) .42f else .56f), TrackTableTitleMaxWidth),
        artistWidth = minOf(text * (if (showAlbum) .30f else .44f), TrackTableDetailMaxWidth),
        albumWidth = if (showAlbum) minOf(text * .28f, TrackTableDetailMaxWidth) else null,
        trailingWidth = trailingWidth,
        timeEndInset = timeEndInset,
    )
}

/**
 * Whether a list earns an Album column: at least a quarter of its tracks name their album. A few
 * stray names (a folder guessed as an album) would otherwise hold a mostly empty column open.
 */
fun List<Track>.namesAlbums(): Boolean =
    isNotEmpty() && count { it.albumName != null } * 4 >= size

/** The album a row can show. YouTube fills the slot with "YouTube Music", which names no album. */
val Track.albumName: String?
    get() = album?.title?.takeIf { title -> title.isNotBlank() && ProviderId.entries.none { it.displayName == title } }

/** Provided once per list by the screen that measured it; [TrackRow] reads it instead of measuring itself. */
val LocalTrackTable = staticCompositionLocalOf<TrackTableColumns?> { null }

/**
 * Quiet column labels for a [TrackTableColumns] list, laid out by the same [LedgerRow] as the rows
 * so each label starts where its column does. Pass the rows' own outer padding as [modifier].
 */
@Composable
fun TrackTableHeader(columns: TrackTableColumns, modifier: Modifier = Modifier) {
    LedgerRow(modifier = modifier) {
        Spacer(Modifier.width(TrackTableCoverSize))
        LedgerHeaderText("Title", Modifier.width(columns.titleWidth))
        LedgerHeaderText("Artist", Modifier.width(columns.artistWidth))
        columns.albumWidth?.let { LedgerHeaderText("Album", Modifier.width(it)) }
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(TrackTableAvailabilityWidth))
        Spacer(Modifier.width(TrackTableSourceWidth))
        Box(Modifier.width(columns.trailingWidth)) {
            columns.timeEndInset?.let { inset ->
                LedgerHeaderText("Time", Modifier.align(Alignment.CenterEnd).padding(end = inset))
            }
        }
    }
}

/** The trailing slot of a table row: fixed width, content packed against its end. */
@Composable
internal fun TrackTableTrailing(columns: TrackTableColumns, content: @Composable () -> Unit) {
    Row(
        Modifier.width(columns.trailingWidth),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
