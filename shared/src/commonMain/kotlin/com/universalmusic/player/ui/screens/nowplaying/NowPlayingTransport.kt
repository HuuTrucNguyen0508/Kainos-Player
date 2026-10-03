package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun NowPlayingTransport(
    track: Track?,
    isPlaying: Boolean,
    buffering: Boolean,
    favorite: Boolean,
    queue: PlaybackQueue,
    repeat: RepeatDisplay,
    shuffle: ShuffleDisplay,
    sleepActive: Boolean,
    sleepRemainingLabel: String?,
    scheme: ColorScheme,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    canSkipNext: () -> Boolean,
    onToggleFavorite: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSleepTimer: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = track != null,
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "Previous",
                modifier = Modifier.size(36.dp),
            )
        }
        FilledIconButton(
            onClick = onTogglePlayPause,
            enabled = track != null,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = scheme.primary,
                contentColor = scheme.onPrimary,
            ),
        ) {
            Icon(
                if (isPlaying || buffering) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (isPlaying || buffering) "Pause" else "Play",
                modifier = Modifier.size(36.dp),
            )
        }
        IconButton(
            onClick = onNext,
            enabled = run {
                queue.shuffle
                queue.repeat
                queue.items.size
                queue.currentIndex
                canSkipNext()
            },
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "Next",
                modifier = Modifier.size(36.dp),
            )
        }
    }

    Spacer(Modifier.height(4.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionTooltip(if (favorite) "Remove from favorites" else "Add to favorites") {
            IconButton(
                enabled = track != null,
                onClick = onToggleFavorite,
            ) {
                Icon(
                    if (favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = if (favorite) "Remove from favorites" else "Add to favorites",
                    tint = if (favorite) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
        ActionTooltip(repeat.tooltip) {
            IconButton(onClick = onCycleRepeat) {
                Icon(
                    if (repeat.one) Icons.Default.RepeatOne else Icons.Default.Repeat,
                    contentDescription = repeat.contentDescription,
                    tint = if (repeat.active) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
        ActionTooltip(shuffle.tooltip) {
            IconToggleButton(
                checked = shuffle.on,
                onCheckedChange = { onToggleShuffle() },
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = scheme.secondaryContainer,
                    checkedContentColor = scheme.onSecondaryContainer,
                    contentColor = scheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = shuffle.contentDescription,
                )
            }
        }
        ActionTooltip("Queue") {
            IconButton(onClick = onOpenQueue) {
                Icon(
                    Icons.AutoMirrored.Filled.QueueMusic,
                    contentDescription = "Open queue",
                )
            }
        }
        ActionTooltip(if (sleepActive) "Sleep timer (on)" else "Sleep timer") {
            IconButton(
                onClick = onOpenSleepTimer,
                enabled = track != null || sleepActive,
            ) {
                Icon(
                    Icons.Default.Bedtime,
                    contentDescription = if (sleepActive) {
                        "Sleep timer active. Adjust or cancel"
                    } else {
                        "Set sleep timer"
                    },
                    tint = if (sleepActive) scheme.primary else scheme.onSurfaceVariant,
                )
            }
        }
    }

    if (sleepRemainingLabel != null) {
        Text(
            sleepRemainingLabel,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
internal fun NowPlayingVolume(
    volume: StateFlow<Float>,
    scheme: ColorScheme,
    onDragVolume: (Float) -> Unit,
    onCommitVolume: (Float) -> Unit,
) {
    val volumeValue by volume.collectAsState()
    var volumeDrag by remember { mutableStateOf<Float?>(null) }
    val shownVolume = volumeDrag ?: volumeValue
    val icon = volumeIconKind(shownVolume)
    Row(
        Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (event.type != PointerEventType.Scroll) continue
                        val scrollY = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                        val current = volumeDrag ?: volume.value
                        val next = volumeAfterScroll(current, scrollY) ?: continue
                        onCommitVolume(next)
                        event.changes.forEach { it.consume() }
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(
            onClick = { onCommitVolume(if (shownVolume > 0.001f) 0f else 1f) },
        ) {
            Icon(
                when (icon) {
                    VolumeIconKind.MUTE -> Icons.AutoMirrored.Filled.VolumeMute
                    VolumeIconKind.DOWN -> Icons.AutoMirrored.Filled.VolumeDown
                    VolumeIconKind.UP -> Icons.AutoMirrored.Filled.VolumeUp
                },
                contentDescription = if (icon == VolumeIconKind.MUTE) "Unmute" else "Mute",
            )
        }
        Slider(
            value = shownVolume,
            onValueChange = {
                volumeDrag = it
                onDragVolume(it)
            },
            onValueChangeFinished = {
                val next = volumeDrag ?: volumeValue
                volumeDrag = null
                onCommitVolume(next)
            },
            modifier = Modifier.weight(1f),
        )
        Text(
            volumePercentLabel(shownVolume),
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 36.dp),
        )
    }
}

/** Hover (desktop) / long-press (touch) label for icon-only actions, so each icon says what it does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionTooltip(label: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        content()
    }
}
