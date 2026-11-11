package com.universalmusic.player.ui.screens

import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.data.library.requiresNetworkToPlay
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.TrackSort
import com.universalmusic.player.ui.theme.LocalSignal
import com.universalmusic.player.ui.theme.SpotifyGreen

/** The sticky band at the top of a Library list: its [content], then a rule. */
@Composable
internal fun LibraryLedgerSurface(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            content()
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
internal fun LibraryLedger(
    countLine: String,
    localCount: Int,
    spotifyCount: Int,
    cachedCount: Int = 0,
    localOnly: Boolean,
    favoritesOnly: Boolean,
    localScanning: Boolean,
    spotifyConnected: Boolean,
    spotifyLoading: Boolean,
    onRefreshSpotify: () -> Unit,
    sortLabel: String,
    sortMenuOpen: Boolean,
    onSortMenuOpen: () -> Unit,
    onSortMenuDismiss: () -> Unit,
    onSortSelected: (TrackSort) -> Unit,
    currentSort: TrackSort,
    playLabel: String,
    onPlay: () -> Unit,
    showActions: Boolean,
) {
    LibraryLedgerSurface {
        LibraryLedgerBar(
            countLine, localCount, spotifyCount, cachedCount, localOnly, favoritesOnly, localScanning,
            spotifyConnected, spotifyLoading, onRefreshSpotify, sortLabel, sortMenuOpen, onSortMenuOpen,
            onSortMenuDismiss, onSortSelected, currentSort, playLabel, onPlay, showActions,
        )
    }
}

/** A list's count and source tallies, with Sort and Play all at the end when [showActions]. */
@Composable
internal fun LibraryLedgerBar(
    countLine: String,
    localCount: Int,
    spotifyCount: Int,
    cachedCount: Int = 0,
    localOnly: Boolean,
    favoritesOnly: Boolean,
    localScanning: Boolean,
    spotifyConnected: Boolean,
    spotifyLoading: Boolean,
    onRefreshSpotify: () -> Unit,
    sortLabel: String,
    sortMenuOpen: Boolean,
    onSortMenuOpen: () -> Unit,
    onSortMenuDismiss: () -> Unit,
    onSortSelected: (TrackSort) -> Unit,
    currentSort: TrackSort,
    playLabel: String,
    onPlay: () -> Unit,
    showActions: Boolean,
    /** Plays the same songs as [onPlay] in a fresh shuffle; null leaves the button out. */
    onShuffle: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                countLine,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when {
                    localScanning -> Text(
                        "Scanning your folders…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        if (localCount > 0 || localOnly || favoritesOnly) {
                            SourceTally(sourceMark(Accents.local), "$localCount local")
                        }
                        if (cachedCount > 0) {
                            SourceTally(sourceMark(Accents.cached), "$cachedCount cached")
                        }
                        if (!localOnly && spotifyConnected) {
                            Row(
                                Modifier
                                    .clip(MaterialTheme.shapes.small)
                                    .clickable(enabled = !spotifyLoading, onClick = onRefreshSpotify)
                                    .semantics { contentDescription = "Refresh Spotify library" }
                                    .padding(vertical = 2.dp, horizontal = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                SourceDot(sourceMark(Accents.spotify))
                                Text(
                                    if (spotifyLoading) "Spotify loading…" else "$spotifyCount Spotify",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (spotifyLoading) {
                                    CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
                                }
                            }
                        }
                    }
                }
            }
        }
        if (showActions) {
            Box {
                TextButton(
                    onClick = onSortMenuOpen,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                ) {
                    Text(sortLabel, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = onSortMenuDismiss) {
                    TrackSort.entries.forEach { sort ->
                        DropdownMenuItem(
                            text = { Text(sort.label()) },
                            onClick = { onSortSelected(sort) },
                            trailingIcon = {
                                if (currentSort == sort) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected")
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            if (onShuffle != null) {
                FilledTonalIconButton(onClick = onShuffle, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Shuffle, contentDescription = "Shuffle", modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
            }
            FilledTonalButton(
                onClick = onPlay,
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(playLabel, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

/** Width of a Library table row's trailing slot: heart, source dots, time and the ⋮ menu. */
internal val LibraryTrailingWidth = 180.dp

/** The time's right edge sits this far in from the slot's end: 8dp of [LibraryTrackTrailing] padding, a 12dp gap, the 40dp menu. */
internal val LibraryTimeEndInset = 60.dp

@Composable
internal fun LibraryTrackTrailing(favorite: Boolean, track: Track) {
    val playable = track.playableSources().map { it.provider }.toSet()
    Row(
        modifier = Modifier.padding(start = 8.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (favorite) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = "App favorite",
                tint = sourceMark(Accents.heart),
                modifier = Modifier.size(16.dp),
            )
        }
        track.sources.map { it.provider }.distinct().forEach { provider ->
            val color = when (provider) {
                ProviderId.LOCAL -> sourceMark(Accents.local)
                ProviderId.SPOTIFY -> sourceMark(Accents.spotify)
                ProviderId.YOUTUBE_MUSIC -> sourceMark(Accents.youtube)
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            SourceDot(color, dimmed = provider !in playable && track.requiresNetworkToPlay())
        }
        Text(
            track.durationMs?.takeIf { it > 0 }?.let { formatTime(it) } ?: "--:--",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun sourceMark(slot: AccentSlot): Color = LocalKainosPalette.current.accent(slot, AccentUse.Mark)

@Composable
private fun SourceTally(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SourceDot(color)
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceDot(color: Color, dimmed: Boolean = false) {
    Box(
        Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(if (dimmed) color.copy(alpha = 0.4f) else color),
    )
}

internal fun songsCountLine(
    visible: Int,
    total: Int,
    queryActive: Boolean,
    favoritesOnly: Boolean,
    readyOffline: Boolean = false,
): String = when {
    favoritesOnly && !queryActive -> countNoun(visible, "favorite", "favorites")
    readyOffline && !queryActive -> "$visible ready offline"
    queryActive -> "$visible of $total ${if (total == 1) "song" else "songs"}"
    else -> countNoun(visible, "song", "songs")
}

internal fun countNoun(n: Int, singular: String, plural: String): String =
    "$n ${if (n == 1) singular else plural}"

private fun TrackSort.label(): String = when (this) {
    TrackSort.NAME_ASCENDING -> "Name A to Z"
    TrackSort.NAME_DESCENDING -> "Name Z to A"
    TrackSort.DURATION_ASCENDING -> "Duration shortest first"
    TrackSort.DURATION_DESCENDING -> "Duration longest first"
    TrackSort.ARTIST_ASCENDING -> "Artist A to Z"
    TrackSort.ALBUM_ASCENDING -> "Album A to Z"
    TrackSort.YEAR_DESCENDING -> "Year newest first"
    TrackSort.RECENTLY_ADDED -> "Recently added"
}

internal fun TrackSort.shortLabel(): String = when (this) {
    TrackSort.NAME_ASCENDING -> "A to Z"
    TrackSort.NAME_DESCENDING -> "Z to A"
    TrackSort.DURATION_ASCENDING -> "Shortest"
    TrackSort.DURATION_DESCENDING -> "Longest"
    TrackSort.ARTIST_ASCENDING -> "Artist"
    TrackSort.ALBUM_ASCENDING -> "Album"
    TrackSort.YEAR_DESCENDING -> "Year"
    TrackSort.RECENTLY_ADDED -> "Recent"
}
