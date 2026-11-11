package com.universalmusic.player.ui.screens

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** At this width the search field, the source chips and the result counts share one line. */
internal val SearchCompactHeaderMinWidth: Dp = 1500.dp

/** The search field's width on that line: room for a long query, not the whole sheet. */
internal val SearchCompactFieldWidth: Dp = 720.dp

/** At this width playlists (or the playlist opened from them) stand beside the track results. */
internal val SearchSplitMinWidth: Dp = 2000.dp

/** The share of a split Search the track results take. */
internal const val SearchTracksShare: Float = .58f

/**
 * Whether Search shows two panes. Only results split, and only when the right pane has something to
 * hold: playlist results or an open playlist. Empty, loading and setup states keep the whole sheet.
 */
internal fun searchSplit(
    width: Dp,
    showingResults: Boolean,
    hasTracks: Boolean,
    hasPlaylists: Boolean,
    playlistOpen: Boolean,
): Boolean = width >= SearchSplitMinWidth && showingResults && hasTracks && (hasPlaylists || playlistOpen)

/** Keep a lone ledger readable rather than separating its marks from its text. */
internal val SingleTrackTableMaxWidth: Dp = 1600.dp
