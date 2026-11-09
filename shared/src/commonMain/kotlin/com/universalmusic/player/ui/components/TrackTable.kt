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
import com.universalmusic.player.domain.model.Track

/** A track list at least this wide lays its rows out as a table; narrower lists keep stacked rows. */
val TrackTableMinWidth: Dp = 1100.dp

internal val TrackTableCoverSize: Dp = 40.dp
internal val TrackTableAvailabilityWidth: Dp = 150.dp
internal val TrackTableSourceWidth: Dp = 84.dp

/**
 * The columns of one wide track list. Every row of the list reads the same value, so a column
 * starts at the same x on every row whatever its trailing content holds.
 */
@Immutable
data class TrackTableColumns(
    val showAlbum: Boolean,
    /** Fixed width of the trailing slot (heart, time, menu); content sits at its end. */
    val trailingWidth: Dp,
    /** How far the time's right edge sits in from the trailing slot's end; null when the list shows no time. */
    val timeEndInset: Dp? = null,
) {
    val titleWeight: Float get() = if (showAlbum) .42f else .56f
    val artistWeight: Float get() = if (showAlbum) .30f else .44f
    val albumWeight: Float get() = .28f
}

/** The table for a list [listWidth] wide, or null when its rows should stay stacked. */
fun trackTableColumns(
    listWidth: Dp,
    showAlbum: Boolean,
    trailingWidth: Dp,
    timeEndInset: Dp? = null,
): TrackTableColumns? =
    if (listWidth >= TrackTableMinWidth) TrackTableColumns(showAlbum, trailingWidth, timeEndInset) else null

/** True when any track names its album; otherwise the list leaves the Album column out. */
fun List<Track>.anyAlbumName(): Boolean = any { !it.album?.title.isNullOrBlank() }

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
        LedgerHeaderText("Title", Modifier.weight(columns.titleWeight))
        LedgerHeaderText("Artist", Modifier.weight(columns.artistWeight))
        if (columns.showAlbum) LedgerHeaderText("Album", Modifier.weight(columns.albumWeight))
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
