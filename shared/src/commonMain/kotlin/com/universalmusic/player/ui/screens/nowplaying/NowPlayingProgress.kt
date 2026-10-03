package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.playback.NowPlayingState
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun NowPlayingProgress(
    nowPlaying: StateFlow<NowPlayingState>,
    onSeek: (Long) -> Unit,
) {
    val now by nowPlaying.collectAsState()
    val trackIdentity = now.queueItemId ?: now.track?.canonicalId
    var scrubPosition by remember(trackIdentity) { mutableStateOf<Float?>(null) }
    val knownDurationMs = knownDurationMs(now.durationMs, now.track?.durationMs)
    val progress = playbackProgress(now.positionMs, knownDurationMs)
    val scheme = MaterialTheme.colorScheme
    if (knownDurationMs == null) {
        // No length yet (e.g. a restored Android folder track before it plays): a bar
        // pinned at 0 beside a running position reads as broken, so draw a quiet rail.
        Box(
            Modifier
                .fillMaxWidth()
                .height(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(scheme.surfaceVariant, CircleShape),
            )
        }
    } else {
        Slider(
            value = scrubPosition ?: progress,
            onValueChange = { scrubPosition = it },
            onValueChangeFinished = {
                val position = scrubPosition
                if (position != null) {
                    onSeek((position * knownDurationMs).toLong())
                }
                scrubPosition = null
            },
            enabled = now.resolved != null && !now.buffering,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            formatTime(displayedPositionMs(scrubPosition, knownDurationMs, now.positionMs)),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Text(
            knownDurationMs?.let(::formatTime) ?: "Length shown once playing",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
    }
}
