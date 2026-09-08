package com.universalmusic.player.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.universalmusic.player.data.library.requiresNetworkToPlay
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.theme.providerColor

private val MaterialCorner = RoundedCornerShape(12.dp)
private val ArtCorner = RoundedCornerShape(16.dp)

@Composable
fun ArtworkImage(
    artwork: Artwork?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    seed: String = contentDescription,
) {
    if (artwork != null) {
        AsyncImage(
            model = artwork.url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(ArtCorner),
        )
    } else {
        Box(
            modifier = modifier
                .clip(ArtCorner)
                .background(placeholderColor(seed)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = seed.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

@Composable
fun ProviderChips(
    providers: Collection<ProviderId>,
    modifier: Modifier = Modifier,
    available: Collection<ProviderId> = providers,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        providers.distinct().forEach { provider ->
            val active = provider in available
            AssistChip(
                onClick = {},
                enabled = false,
                label = {
                    Text(
                        provider.displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = if (active) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    },
                    disabledLabelColor = if (active) {
                        providerColor(provider.displayName)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    },
                ),
                border = AssistChipDefaults.assistChipBorder(
                    enabled = false,
                    disabledBorderColor = MaterialTheme.colorScheme.outlineVariant,
                ),
            )
        }
    }
}

@Composable
fun ProviderStatusRow(
    statuses: Map<ProviderId, ProviderState>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        statuses.forEach { (provider, state) ->
            val tone = when (state) {
                ProviderState.AVAILABLE -> providerColor(provider.displayName)
                ProviderState.LOADING -> MaterialTheme.colorScheme.primary
                ProviderState.RATE_LIMITED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.outline
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialCorner,
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = provider.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        color = tone,
                    )
                    Text(
                        text = state.name.lowercase().replace('_', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialCorner,
        color = Color.Transparent,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(track.artwork, track.title, Modifier.size(56.dp), track.title)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(track.artistLine)
                        if (track.requiresNetworkToPlay()) append(" · Needs connection")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                ProviderChips(
                    providers = track.sources.map { it.provider },
                    available = track.playableSources().map { it.provider },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun AlbumRow(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onClick,
        shape = MaterialCorner,
        color = Color.Transparent,
    ) {
        Row(
            Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(album.artwork, album.title, Modifier.size(56.dp), album.title)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(album.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(album.artists.joinToString { it.name })
                        if (album.tracks.isNotEmpty()) {
                            if (isNotEmpty()) append(" · ")
                            append("${album.tracks.size} tracks")
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun MiniPlayerBar(
    title: String,
    artist: String,
    artwork: Artwork?,
    isPlaying: Boolean,
    providerLabel: String?,
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    canSkipNext: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkImage(artwork, title, Modifier.size(48.dp), title)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(artist, providerLabel).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onToggle) {
                Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, contentDescription = if (isPlaying) "Pause" else "Play")
            }
            IconButton(onClick = onNext, enabled = canSkipNext) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next")
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier.padding(horizontal = 20.dp, vertical = 12.dp),
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun placeholderColor(seed: String): Color {
    val hash = seed.hashCode()
    // Soft olive placeholders that sit in the Caelestia family.
    val r = 55 + ((hash ushr 16) and 0x2F)
    val g = 70 + ((hash ushr 8) and 0x3F)
    val b = 40 + (hash and 0x2F)
    return Color(r, g, b)
}
