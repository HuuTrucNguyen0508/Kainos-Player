package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.components.SectionRule
import com.universalmusic.player.ui.components.Slip
import com.universalmusic.player.ui.components.SourceKind
import com.universalmusic.player.ui.components.SourceMark
import com.universalmusic.player.ui.components.albumLightStrip
import com.universalmusic.player.ui.components.rememberAlbumLight
import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.providerColor

@Composable
internal fun FavoritesHero(
    favorites: FavoritesSummary,
    stacked: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKainosPalette.current
    val light by rememberAlbumLight(favorites.covers.firstOrNull()?.url)
    val mosaic = if (stacked) 112.dp else 176.dp
    val actions: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onPlay, enabled = favorites.count > 0) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play favorites", maxLines = 1)
            }
            FilledTonalButton(onClick = onShuffle, enabled = favorites.count > 0) {
                Icon(Icons.Default.Shuffle, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Shuffle", maxLines = 1)
            }
        }
    }
    Slip(modifier) {
        Box(Modifier.fillMaxSize().albumLightStrip(light, palette.isDark)) {
            if (stacked) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        CoverMosaic(favorites.covers, mosaic)
                        FavoritesHeadline(favorites, stacked = true, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(14.dp))
                    actions()
                }
            } else {
                Row(
                    Modifier.fillMaxSize().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CoverMosaic(favorites.covers, mosaic)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = mosaic),
                        verticalArrangement = Arrangement.SpaceBetween,
                    ) {
                        FavoritesHeadline(favorites, stacked = false)
                        Spacer(Modifier.height(12.dp))
                        actions()
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoritesHeadline(favorites: FavoritesSummary, stacked: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                tint = palette.accent(Accents.heart, AccentUse.Mark, palette.sheet),
                modifier = Modifier.size(14.dp),
            )
            Text("Favorites", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            if (favorites.count > 0) "${favorites.count} hearted ${if (favorites.count == 1) "song" else "songs"}" else "No hearts yet",
            style = (if (stacked) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge).copy(fontFamily = tokens.display),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (favorites.count == 0) {
            Text(
                "Heart songs in Library and they gather here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FlowRow(
                Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (favorites.local > 0) SourceMark(SourceKind.Local, "${favorites.local} local", on = palette.sheet)
                if (favorites.spotify > 0) SourceMark(SourceKind.Spotify, "${favorites.spotify} Spotify", on = palette.sheet)
                if (favorites.youtube > 0) SourceMark(SourceKind.YouTube, "${favorites.youtube} YouTube", on = palette.sheet)
            }
        }
    }
}

/** Up to four real covers in a two-by-two block; a single heart tile when the listener has none. */
@Composable
private fun CoverMosaic(covers: List<Artwork>, size: androidx.compose.ui.unit.Dp) {
    val palette = LocalKainosPalette.current
    val tokens = LocalSkinTokens.current
    val outer = RoundedCornerShape(tokens.rounding.large)
    val gap = 3.dp
    Box(Modifier.size(size).clip(outer).background(palette.accentContainer(Accents.heart))) {
        when {
            covers.isEmpty() -> Icon(
                Icons.Default.Favorite,
                contentDescription = null,
                tint = palette.accent(Accents.heart, AccentUse.Mark, palette.accentContainer(Accents.heart)),
                modifier = Modifier.size(size / 3).align(Alignment.Center),
            )
            covers.size < FAVORITES_HERO_COVERS -> ArtworkImage(covers.first(), "Hearted covers", Modifier.size(size), shape = outer)
            else -> Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                covers.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        pair.forEach { art ->
                            ArtworkImage(art, "Hearted cover", Modifier.size((size - gap) / 2), shape = RoundedCornerShape(2.dp))
                        }
                    }
                }
            }
        }
    }
}

private val LibraryMark.slot: AccentSlot
    get() = when (this) {
        LibraryMark.LOCAL -> Accents.local
        LibraryMark.HEARTS -> Accents.heart
        LibraryMark.PLAYLISTS -> Accents.playlist
        LibraryMark.SPOTIFY -> Accents.spotify
        LibraryMark.YOUTUBE -> Accents.youtube
    }

private val LibraryMark.icon: ImageVector
    get() = when (this) {
        LibraryMark.LOCAL -> Icons.Default.SdStorage
        LibraryMark.HEARTS -> Icons.Default.Favorite
        LibraryMark.PLAYLISTS -> Icons.AutoMirrored.Filled.QueueMusic
        LibraryMark.SPOTIFY -> Icons.Default.GraphicEq
        LibraryMark.YOUTUBE -> Icons.Default.PlayCircle
    }

/** "Your library": one coloured row per place music comes from, each opening where the listener acts on it. */
@Composable
internal fun LibraryCard(
    lines: List<LibraryLine>,
    onOpen: (LibraryDoor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalKainosPalette.current
    val rounding = LocalSkinTokens.current.rounding
    Slip(modifier) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            SectionRule("Your library", accent = Accents.library)
            lines.forEach { line ->
                val fill = palette.accentContainer(line.mark.slot)
                val mark = palette.accent(line.mark.slot, AccentUse.Mark, fill)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(rounding.small))
                        .clickable { onOpen(line.door) }
                        .padding(vertical = 7.dp, horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(fill),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(line.mark.icon, contentDescription = null, tint = mark, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(line.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            line.detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiscoverBlock(
    playlist: Playlist,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            playlist.artwork,
            playlist.title,
            Modifier.size(72.dp),
            playlist.title,
        )
        Column(Modifier.weight(1f)) {
            Text(
                "New this week",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                playlist.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    busy -> "Loading tracks…"
                    else -> listOfNotNull(
                        playlist.trackCount?.let { "$it tracks" },
                        "Spotify",
                    ).joinToString(" · ")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
internal fun EmptyDoors(
    localState: ProviderState,
    spotifyState: ProviderState,
    youtubeState: ProviderState,
    onOpenSettings: (() -> Unit)?,
    onOpenSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("Nothing to play yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Kainos plays from three places. Open one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        EmptyDoor(
            provider = ProviderId.LOCAL,
            state = localState,
            name = "Local library",
            action = "Add a music folder in Settings",
            onClick = onOpenSettings,
        )
        EmptyDoor(
            provider = ProviderId.SPOTIFY,
            state = spotifyState,
            name = "Spotify",
            action = "Connect in Settings",
            onClick = onOpenSettings,
        )
        EmptyDoor(
            provider = ProviderId.YOUTUBE_MUSIC,
            state = youtubeState,
            name = "YouTube Music",
            action = "Search for anything and play it",
            onClick = onOpenSearch,
        )
    }
}

@Composable
private fun EmptyDoor(
    provider: ProviderId,
    state: ProviderState,
    name: String,
    action: String,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = "$name. $action" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(provider, state)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                action,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendDot(provider: ProviderId, state: ProviderState) {
    when (state) {
        ProviderState.AVAILABLE -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(providerColor(provider.displayName)),
        )
        ProviderState.LOADING -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        ProviderState.RATE_LIMITED -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        else -> Box(
            Modifier
                .size(8.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}
