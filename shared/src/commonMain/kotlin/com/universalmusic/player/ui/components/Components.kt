package com.universalmusic.player.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.library.requiresNetworkToPlay
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.Accents
import com.universalmusic.player.ui.theme.LocalSkinTokens
import com.universalmusic.player.ui.theme.Skin
import com.universalmusic.player.ui.theme.providerColor

@Composable
private fun rowShape(): Shape = RoundedCornerShape(LocalSkinTokens.current.rounding.normal)

@Composable
private fun artShape(): Shape = RoundedCornerShape(LocalSkinTokens.current.rounding.large)

@Composable
fun ArtworkImage(
    artwork: Artwork?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    seed: String = contentDescription,
    shape: Shape = artShape(),
) {
    // Failed Coil loads used to leave a blank area; letter tile is only for null Artwork.
    // Treat load errors like missing art so Now Playing never shows an empty square.
    var loadFailed by remember(artwork?.url) { mutableStateOf(false) }
    if (artwork != null && !loadFailed) {
        AsyncImage(
            model = artwork.url,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(shape),
            onError = { loadFailed = true },
        )
    } else {
        PlaceholderArt(seed, modifier.clip(shape))
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
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        providers.distinct().forEach { provider ->
            SourceMark(
                SourceKind.of(provider),
                if (provider in available) provider.displayName else "${provider.displayName}, unavailable",
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
            Slip {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    SourceMark(SourceKind.of(provider), provider.displayName, showIcon = true)
                    Text(
                        text = state.name.lowercase().replace('_', ' '),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state == ProviderState.RATE_LIMITED) tone else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * One track as a ledger line: cover, title and artist, then where it plays from. Offline state is an
 * [AvailabilityMark]; the source is a [SourceMark], so every row carries its colours with a label.
 */
@Composable
fun TrackRow(
    track: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    compact: Boolean = false,
    availability: TrackAvailabilityInfo? = null,
) {
    val artSize = if (compact) 40.dp else 48.dp
    val folio = LocalSkinTokens.current.skin == Skin.FOLIO
    val status = availability?.status?.takeIf { it != TrackAvailability.AVAILABLE_LOCALLY }
    val needsNetwork = availability == null && track.requiresNetworkToPlay()
    val source = (track.playableSources().firstOrNull() ?: track.sources.firstOrNull())?.provider
    LedgerRow(modifier = modifier, onClick = onClick) {
        Box {
            ArtworkImage(
                track.artwork, track.title, Modifier.size(artSize), track.title,
                shape = RoundedCornerShape(LocalSkinTokens.current.rounding.small),
            )
            if (folio && source != null) {
                // The launcher's tile underline, in the source's hue.
                Box(
                    Modifier.align(Alignment.BottomCenter).offset(y = 5.dp).size(16.dp, 2.dp)
                        .background(LocalKainosPalette.current.accent(SourceKind.of(source).slot, AccentUse.Mark)),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                track.artistLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (status != null && availability != null) {
                AvailabilityMark(status, availability.label, Modifier.padding(top = 2.dp))
            } else if (needsNetwork) {
                AvailabilityMark(TrackAvailability.WAITING_FOR_NETWORK, "Needs connection", Modifier.padding(top = 2.dp))
            }
            if (availability?.status == TrackAvailability.DOWNLOADING) {
                val progress = availability.progress
                val color = LocalKainosPalette.current.accent(Accents.downloading, AccentUse.Mark)
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        color = color,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        color = color,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(2.dp),
                    )
                }
            }
        }
        if (!compact && source != null) {
            SourceMark(SourceKind.of(source), source.shortLabel)
        }
        trailing?.invoke()
    }
}

private val ProviderId.shortLabel: String
    get() = when (this) {
        ProviderId.LOCAL -> "Local"
        ProviderId.SPOTIFY -> "Spotify"
        ProviderId.YOUTUBE_MUSIC -> "YouTube"
        ProviderId.SAMPLE -> "Kainos"
    }

@Composable
fun AlbumRow(
    album: Album,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    LedgerRow(modifier = modifier, onClick = onClick) {
        ArtworkImage(
            album.artwork, album.title, Modifier.size(48.dp), album.title,
            shape = RoundedCornerShape(LocalSkinTokens.current.rounding.small),
        )
        Column(Modifier.weight(1f)) {
            Text(album.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                buildString {
                    append(album.artists.joinToString { it.name })
                    if (album.tracks.isNotEmpty()) {
                        if (isNotEmpty()) append(", ")
                        append("${album.tracks.size} tracks")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke()
    }
}

/**
 * The launcher's now-playing slip, reused as the mini player: cover, serif title, artist and time,
 * a round play button, and the skin's compact seek drawing along the top edge.
 */
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
    progress: Float = 0f,
    buffering: Boolean = false,
    modifier: Modifier = Modifier,
    durationMs: Long? = null,
) {
    val palette = LocalKainosPalette.current
    val scheme = MaterialTheme.colorScheme
    Slip(modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), onClick = onOpen) {
        // Same album light as Now Playing, as a quiet wash under the cover.
        val light by rememberAlbumLight(artwork?.url)
        Column(Modifier.fillMaxWidth().albumLightStrip(light, palette.isDark)) {
            Row(
                Modifier.padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(artwork, title, Modifier.size(44.dp), title, shape = RoundedCornerShape(LocalSkinTokens.current.rounding.normal))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(artist, providerLabel).joinToString(", "),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Surface(
                    onClick = onToggle,
                    shape = LocalSkinTokens.current.rounding.full,
                    color = scheme.primaryContainer,
                    contentColor = scheme.onPrimaryContainer,
                    border = BorderStroke(1.dp, palette.rule),
                    modifier = Modifier.size(40.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                IconButton(onClick = onNext, enabled = canSkipNext) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next")
                }
            }
            if (buffering) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).height(2.dp))
            } else {
                SeekTrack(
                    fraction = progress,
                    durationMs = durationMs,
                    isPlaying = isPlaying,
                    kind = SeekKind.Compact,
                    modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 4.dp),
                )
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    SectionRule(title, modifier.padding(horizontal = 20.dp, vertical = 4.dp))
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
