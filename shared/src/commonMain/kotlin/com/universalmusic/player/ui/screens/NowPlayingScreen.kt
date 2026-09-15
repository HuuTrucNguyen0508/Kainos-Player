package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.spotify.SpotifyConnectDevice
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.platform.platformLabel
import com.universalmusic.player.platform.requiresExplicitSpotifyDevice
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.theme.providerColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun NowPlayingScreen(
    container: AppContainer,
    onOpenQueue: () -> Unit,
    compact: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    val now by container.player.nowPlaying.collectAsState()
    val queue by container.player.queue.queue.collectAsState()
    val volume by container.player.volume.collectAsState()
    val settings by container.settings.collectAsState()
    val spotifyState by container.spotify.state.collectAsState()
    val scope = rememberCoroutineScope()
    val track = now.track
    var scrubPosition by remember(track?.canonicalId) { mutableStateOf<Float?>(null) }
    var volumeDrag by remember { mutableStateOf<Float?>(null) }
    var spotifyDevices by remember { mutableStateOf<List<SpotifyConnectDevice>>(emptyList()) }
    var spotifyDevicesLoaded by remember { mutableStateOf(false) }
    var spotifyDeviceBusy by remember { mutableStateOf(false) }
    var spotifyDeviceNotice by remember { mutableStateOf<String?>(null) }
    val knownDurationMs = now.durationMs?.takeIf { it > 0 } ?: track?.durationMs?.takeIf { it > 0 }
    val progress = if (knownDurationMs != null) {
        (now.positionMs.toFloat() / knownDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val provider = now.resolved?.source?.provider
    val quality = now.resolved?.source?.quality
    val spineLabel = listOfNotNull(provider?.displayName, quality?.label).joinToString(" · ").ifBlank { "Kainos" }
    val showSpotifyOutput = requiresExplicitSpotifyDevice() &&
        (provider == ProviderId.SPOTIFY || track?.sourceFor(ProviderId.SPOTIFY) != null) &&
        (spotifyState == ProviderState.AVAILABLE || spotifyState == ProviderState.RATE_LIMITED)

    Column(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(if (compact) 16.dp else 24.dp),
    ) {
        if (onClose != null) {
            TextButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.Start)
                    .padding(bottom = 4.dp),
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null)
                Text("Back")
            }
            Spacer(Modifier.height(16.dp))
        }

        ArtworkImage(
            artwork = track?.artwork,
            contentDescription = track?.title ?: "Artwork",
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = 360.dp)
                .fillMaxWidth()
                .then(
                    if (compact) {
                        Modifier.aspectRatio(1f)
                    } else {
                        Modifier
                            .heightIn(max = 280.dp)
                            .aspectRatio(1f)
                    },
                ),
            seed = track?.title ?: "U",
        )
        Spacer(Modifier.height(12.dp))
        Text(
            spineLabel,
            style = MaterialTheme.typography.labelLarge,
            color = provider?.displayName?.let(::providerColor) ?: MaterialTheme.colorScheme.primary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        Spacer(Modifier.height(20.dp))
        Text(
            track?.title ?: "Nothing playing",
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track?.artistLine ?: "Pick a track from Home, Search, or Library",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        quality?.technicalDetail?.let { detail ->
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
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
                style = MaterialTheme.typography.labelMedium,
            )
            Text(
                knownDurationMs?.let(::formatTime) ?: "--:--",
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { container.player.skipToPrevious() }, enabled = track != null) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(34.dp))
            }
            FilledIconButton(
                onClick = { container.player.togglePlayPause() },
                enabled = track != null,
                modifier = Modifier.size(64.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(
                    if (now.isPlaying || now.buffering) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (now.isPlaying || now.buffering) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                )
            }
            IconButton(
                onClick = { container.player.skipToNext() },
                enabled = run {
                    queue.shuffle
                    queue.repeat
                    queue.items.size
                    queue.currentIndex
                    container.player.canSkipNext()
                },
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Next", Modifier.size(34.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconToggleButton(
                checked = queue.shuffle,
                onCheckedChange = { container.player.toggleShuffle() },
                colors = IconButtonDefaults.iconToggleButtonColors(
                    checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                    checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            ) {
                Icon(
                    Icons.Default.Shuffle,
                    contentDescription = if (queue.shuffle) "Turn shuffle off" else "Turn shuffle on",
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
                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = "Queue")
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
        if (compact || platformLabel() == "Linux") {
            Spacer(Modifier.height(8.dp))
            val shownVolume = volumeDrag ?: volume
            Row(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Main)
                                if (event.type != PointerEventType.Scroll) continue
                                val scrollY = event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
                                if (scrollY == 0f) continue
                                // Wheel up (negative Y on desktop) raises volume.
                                val step = (-scrollY * 0.04f).coerceIn(-0.2f, 0.2f)
                                val current = volumeDrag ?: container.player.volume.value
                                val next = (current + step).coerceIn(0f, 1f)
                                container.setPlaybackVolume(next)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = {
                        container.setPlaybackVolume(if (shownVolume > 0.001f) 0f else 1f)
                    },
                ) {
                    Icon(
                        when {
                            shownVolume <= 0.001f -> Icons.AutoMirrored.Filled.VolumeMute
                            shownVolume < 0.5f -> Icons.AutoMirrored.Filled.VolumeDown
                            else -> Icons.AutoMirrored.Filled.VolumeUp
                        },
                        contentDescription = if (shownVolume <= 0.001f) "Unmute" else "Mute",
                    )
                }
                Slider(
                    value = shownVolume,
                    onValueChange = { volumeDrag = it; container.player.setVolume(it) },
                    onValueChangeFinished = {
                        val next = volumeDrag ?: volume
                        volumeDrag = null
                        container.setPlaybackVolume(next)
                    },
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${(shownVolume * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(min = 36.dp),
                )
            }
        }
        now.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (showSpotifyOutput) {
            Spacer(Modifier.height(16.dp))
            Text("Spotify output", style = MaterialTheme.typography.titleSmall)
            Text(
                settings.spotifyPlaybackDeviceName?.let { "Playing through: $it" }
                    ?: "Pick a Connect device. Sound comes from that device, not from Kainos.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                enabled = !spotifyDeviceBusy,
                onClick = {
                    spotifyDeviceBusy = true
                    spotifyDeviceNotice = null
                    scope.launch {
                        try {
                            spotifyDevices = container.spotify.getConnectDevices()
                            spotifyDevicesLoaded = true
                            val selectedId = container.settings.value.spotifyPlaybackDeviceId
                            if (selectedId != null && spotifyDevices.none { it.id == selectedId }) {
                                container.updateSettings {
                                    it.copy(spotifyPlaybackDeviceId = null, spotifyPlaybackDeviceName = null)
                                }
                                spotifyDeviceNotice = "Previous device went offline. Select another below."
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            spotifyDeviceNotice = failure.message ?: "Could not load Spotify devices."
                        } finally {
                            spotifyDeviceBusy = false
                        }
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(if (spotifyDeviceBusy) "Refreshing…" else "Refresh devices")
            }
            if (spotifyDevicesLoaded && spotifyDevices.isEmpty()) {
                Text(
                    "No devices online. Open Spotify on this phone or another Premium device, then refresh.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            spotifyDevices.forEach { device ->
                val selected = settings.spotifyPlaybackDeviceId == device.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selected,
                            enabled = !spotifyDeviceBusy && !device.isRestricted,
                        ) {
                            spotifyDeviceBusy = true
                            spotifyDeviceNotice = null
                            scope.launch {
                                try {
                                    container.updateSettings {
                                        it.copy(
                                            spotifyPlaybackDeviceId = device.id,
                                            spotifyPlaybackDeviceName = device.name,
                                        )
                                    }
                                    spotifyDeviceNotice = "Spotify will play on ${device.name}. Press play again."
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Exception) {
                                    spotifyDeviceNotice = failure.message ?: "Could not save device."
                                } finally {
                                    spotifyDeviceBusy = false
                                }
                            }
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected, onClick = null, enabled = !device.isRestricted)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(device.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buildString {
                                append(device.type)
                                if (device.isActive) append(" · Active")
                                if (device.volumePercent == 0) append(" · Muted")
                                if (device.isRestricted) append(" · Restricted")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            spotifyDeviceNotice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
