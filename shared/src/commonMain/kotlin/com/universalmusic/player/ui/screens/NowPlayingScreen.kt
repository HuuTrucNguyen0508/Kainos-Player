package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.theme.providerColor

@Composable
fun NowPlayingScreen(
    container: AppContainer,
    onOpenQueue: () -> Unit,
    compact: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    val now by container.player.nowPlaying.collectAsState()
    val queue by container.player.queue.queue.collectAsState()
    val track = now.track
    var scrubPosition by remember(track?.canonicalId) { mutableStateOf<Float?>(null) }
    val knownDurationMs = now.durationMs?.takeIf { it > 0 } ?: track?.durationMs?.takeIf { it > 0 }
    val progress = if (knownDurationMs != null) {
        (now.positionMs.toFloat() / knownDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(if (compact) 20.dp else 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (onClose != null) {
            TextButton(onClick = onClose, modifier = Modifier.align(Alignment.Start)) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                Text("Back to browsing")
            }
        }
        ArtworkImage(
            artwork = track?.artwork,
            contentDescription = track?.title ?: "Artwork",
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(if (compact) 0.86f else 0.94f)
                .aspectRatio(1f),
            seed = track?.title ?: "U",
        )
        Spacer(Modifier.height(24.dp))
        Text(
            track?.title ?: "Nothing playing",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track?.artistLine ?: "Choose a track from Search or Home",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val provider = now.resolved?.source?.provider
        val quality = now.resolved?.source?.quality
        if (provider != null || quality != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(provider?.displayName, quality?.label).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                color = provider?.displayName?.let(::providerColor) ?: MaterialTheme.colorScheme.primary,
            )
            quality?.technicalDetail?.let { detail ->
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        now.resolved?.reason?.takeIf { it.isNotBlank() && now.fallback == null }?.let { reason ->
            Text(
                reason,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        now.fallback?.let {
            Text(
                it.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        if (now.buffering) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Loading audio…", style = MaterialTheme.typography.labelSmall)
        }
        Slider(
            value = scrubPosition ?: progress,
            onValueChange = { scrubPosition = it },
            onValueChangeFinished = {
                val position = scrubPosition
                if (position != null && knownDurationMs != null) {
                    container.player.seekTo((position * knownDurationMs).toLong())
                }
                scrubPosition = null
            },
            enabled = now.resolved != null && knownDurationMs != null && !now.buffering,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                formatTime(scrubPosition?.let { (it * (knownDurationMs ?: 0)).toLong() } ?: now.positionMs),
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                knownDurationMs?.let(::formatTime) ?: "--:--",
                style = MaterialTheme.typography.labelSmall,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            IconButton(onClick = { container.player.skipToPrevious() }, enabled = track != null) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
            }
            IconButton(onClick = { container.player.togglePlayPause() }, enabled = track != null) {
                Icon(
                    if (now.isPlaying || now.buffering) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (now.isPlaying || now.buffering) "Pause" else "Play",
                    modifier = Modifier.size(56.dp),
                )
            }
            IconButton(
                onClick = { container.player.skipToNext() },
                enabled = run {
                    queue.shuffle
                    queue.repeat
                    queue.items.size
                    queue.currentIndex
                    container.player.queue.nextIndex() != null
                },
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", Modifier.size(36.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconButton(onClick = { container.player.toggleShuffle() }) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = if (queue.shuffle) "Turn shuffle off" else "Turn shuffle on",
                    tint = if (queue.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { container.player.cycleRepeat() }) {
                Icon(
                    if (queue.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = "Repeat ${queue.repeat.name.lowercase()}. Change repeat mode",
                    tint = if (queue.repeat == RepeatMode.OFF) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                )
            }
            IconButton(onClick = onOpenQueue) {
                Icon(Icons.Default.QueueMusic, contentDescription = "Queue")
            }
            IconButton(
                enabled = track != null,
                onClick = {
                    track?.let {
                        val favorite = container.library.toggleFavorite(it)
                        container.player.setFavorite(favorite)
                    }
                },
            ) {
                Icon(
                    if (now.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (now.favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (now.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        now.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
