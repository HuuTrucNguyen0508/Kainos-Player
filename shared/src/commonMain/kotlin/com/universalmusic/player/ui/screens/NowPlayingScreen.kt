package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bedtime
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.cache.HEARTED_AUDIO_CACHE_PROVIDER_PREFIX
import com.universalmusic.player.data.cache.PlaybackSourceKind
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.spotify.SpotifyConnectDevice
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.SleepTimerDurationPresetsMs
import com.universalmusic.player.domain.playback.SleepTimerMode
import com.universalmusic.player.domain.playback.formatSleepTimerRemaining
import com.universalmusic.player.domain.playback.friendlyPlaybackMessage
import com.universalmusic.player.platform.requiresExplicitSpotifyDevice
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.theme.providerColor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val NowPlayingArtCorner = RoundedCornerShape(20.dp)

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
    val sleepTimer by container.sleepTimer.state.collectAsState()
    val scope = rememberCoroutineScope()
    // Identity stays fixed across metadata/artwork refresh for the same queue entry.
    val trackIdentity = now.queueItemId ?: now.track?.canonicalId
    val track = now.track
    var scrubPosition by remember(trackIdentity) { mutableStateOf<Float?>(null) }
    var volumeDrag by remember { mutableStateOf<Float?>(null) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showMatchCorrectionDialog by remember { mutableStateOf(false) }
    var spotifyDevices by remember { mutableStateOf<List<SpotifyConnectDevice>>(emptyList()) }
    var spotifyDevicesLoaded by remember { mutableStateOf(false) }
    var spotifyDeviceBusy by remember { mutableStateOf(false) }
    var spotifyDeviceNotice by remember { mutableStateOf<String?>(null) }
    var showAudioDetails by remember(trackIdentity) { mutableStateOf(false) }
    var showErrorDiagnostics by remember(now.error) { mutableStateOf(false) }
    val knownDurationMs = now.durationMs?.takeIf { it > 0 } ?: track?.durationMs?.takeIf { it > 0 }
    val progress = if (knownDurationMs != null) {
        (now.positionMs.toFloat() / knownDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val provider = now.resolved?.source?.provider
    val quality = now.resolved?.source?.quality
    val downloads by container.heartedAudio.downloads.collectAsState()
    val availability = remember(track, downloads, now.resolved?.source?.providerTrackId) {
        track?.let { container.trackAvailability(it) }
    }
    val actualSourceLabel = when {
        now.resolved?.source?.providerTrackId?.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX) == true -> {
            if (availability?.isSpotifyViaYouTubeMatch == true) {
                "Playing from YouTube match cache"
            } else {
                "Playing from YouTube cache"
            }
        }
        provider == ProviderId.LOCAL -> "Playing from local file"
        provider == ProviderId.SPOTIFY -> "Playing from Spotify stream"
        provider == ProviderId.YOUTUBE_MUSIC -> "Playing from YouTube stream"
        provider != null -> "Playing from ${provider.displayName}"
        else -> null
    }
    val providerLabel = provider?.displayName
    val qualityLabel = quality?.label
    val showSpotifyOutput = requiresExplicitSpotifyDevice() &&
        (provider == ProviderId.SPOTIFY || track?.sourceFor(ProviderId.SPOTIFY) != null) &&
        (spotifyState == ProviderState.AVAILABLE || spotifyState == ProviderState.RATE_LIMITED)
    val canCorrectMatch = track != null && (
        availability?.isSpotifyViaYouTubeMatch == true ||
            availability?.playbackSource == PlaybackSourceKind.SPOTIFY_VIA_YOUTUBE_CACHE ||
            (
                track.canonicalId.startsWith("spotify:") &&
                    availability?.status == TrackAvailability.CACHED
                )
        )
    val scheme = MaterialTheme.colorScheme
    val washSeed = track?.artwork?.url ?: track?.canonicalId ?: track?.title
    val washColor = remember(washSeed, providerLabel, scheme.primary, scheme.surface) {
        artworkWashColor(
            seed = washSeed,
            providerName = providerLabel,
            surface = scheme.surface,
            primary = scheme.primary,
        )
    }
    val horizontalPad = if (compact) 16.dp else 20.dp
    val detailLines = buildList {
        quality?.technicalDetail?.let(::add)
        now.resolved?.reason?.takeIf { it.isNotBlank() }?.let(::add)
        now.fallback?.message?.let(::add)
    }
    val hasExtraChrome = showSpotifyOutput || now.error != null || detailLines.isNotEmpty()

    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .background(
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to washColor.copy(alpha = if (scheme.background.luminance() < 0.5f) 0.28f else 0.18f),
                        0.42f to washColor.copy(alpha = if (scheme.background.luminance() < 0.5f) 0.10f else 0.08f),
                        1f to Color.Transparent,
                    ),
                ),
            )
            .navigationBarsPadding(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val shortScreen = maxHeight < 640.dp
            // Tall enough: the first screen holds artwork, transport, secondary actions, and the
            // provider/quality footer, with artwork absorbing spare height. Availability, audio
            // details, diagnostics, and Spotify output scroll below. Short screens scroll it all.
            val pinControls = !shortScreen
            val verticalPad = if (compact) 12.dp else 16.dp
            val artMax = when {
                compact -> 280.dp
                shortScreen -> 220.dp
                else -> 360.dp
            }
            val primaryModifier = if (pinControls) {
                Modifier
                    .fillMaxWidth()
                    .height(maxHeight - verticalPad * 2)
            } else {
                Modifier.fillMaxWidth()
            }

            val scrollState = rememberScrollState()
            // Expanded details and diagnostics live below the first screen; bring them into view.
            LaunchedEffect(showAudioDetails, showErrorDiagnostics) {
                if (showAudioDetails || showErrorDiagnostics) {
                    scrollState.animateScrollTo(scrollState.maxValue)
                }
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = horizontalPad, vertical = verticalPad),
            ) {
                Column(primaryModifier) {
                    if (onClose != null) {
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .align(Alignment.Start)
                                .padding(bottom = 8.dp),
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                contentDescription = "Close now playing",
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    key(trackIdentity) {
                        NowPlayingArtwork(
                            title = track?.title,
                            artwork = track?.artwork,
                            artMax = artMax,
                            expand = pinControls,
                            compact = compact,
                        )
                    }

                    Spacer(Modifier.height(if (compact) 12.dp else 16.dp))

                    Text(
                        track?.title ?: "Nothing playing",
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Start,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        track?.artistLine ?: "Pick a track from Home, Search, or Library",
                        style = MaterialTheme.typography.bodyLarge,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 2.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    if (now.buffering) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Loading audio…",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
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
                            formatTime(
                                scrubPosition?.let { (it * (knownDurationMs ?: 0)).toLong() } ?: now.positionMs,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                        Text(
                            knownDurationMs?.let(::formatTime) ?: "--:--",
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        IconButton(
                            onClick = { container.player.skipToPrevious() },
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
                            onClick = { container.player.togglePlayPause() },
                            enabled = track != null,
                            modifier = Modifier.size(72.dp),
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = scheme.primary,
                                contentColor = scheme.onPrimary,
                            ),
                        ) {
                            Icon(
                                if (now.isPlaying || now.buffering) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (now.isPlaying || now.buffering) "Pause" else "Play",
                                modifier = Modifier.size(36.dp),
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
                                contentDescription = if (now.favorite) {
                                    "Remove from favorites"
                                } else {
                                    "Add to favorites"
                                },
                                tint = if (now.favorite) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { container.player.cycleRepeat() }) {
                            val repeatOn = queue.repeat != RepeatMode.OFF
                            Icon(
                                if (queue.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Repeat ${queue.repeat.name.lowercase()}. Change repeat mode",
                                tint = if (repeatOn) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                        IconToggleButton(
                            checked = queue.shuffle,
                            onCheckedChange = { container.player.toggleShuffle() },
                            colors = IconButtonDefaults.iconToggleButtonColors(
                                checkedContainerColor = scheme.secondaryContainer,
                                checkedContentColor = scheme.onSecondaryContainer,
                                contentColor = scheme.onSurfaceVariant,
                            ),
                        ) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = if (queue.shuffle) "Turn shuffle off" else "Turn shuffle on",
                            )
                        }
                        IconButton(onClick = onOpenQueue) {
                            Icon(
                                Icons.AutoMirrored.Filled.QueueMusic,
                                contentDescription = "Open queue",
                            )
                        }
                        IconButton(
                            onClick = { showSleepTimerDialog = true },
                            enabled = track != null || sleepTimer.active,
                        ) {
                            Icon(
                                Icons.Default.Bedtime,
                                contentDescription = if (sleepTimer.active) {
                                    "Sleep timer active. Adjust or cancel"
                                } else {
                                    "Set sleep timer"
                                },
                                tint = if (sleepTimer.active) scheme.primary else scheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (sleepTimer.active) {
                        val remainingLabel = when (val mode = sleepTimer.mode) {
                            is SleepTimerMode.Duration ->
                                sleepTimer.remainingMs?.let { "Sleep · ${formatSleepTimerRemaining(it)}" }
                                    ?: "Sleep timer"
                            is SleepTimerMode.EndOfTrack -> "Sleep · end of track"
                            null -> "Sleep timer"
                        }
                        Text(
                            remainingLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = scheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                            textAlign = TextAlign.Center,
                        )
                    }

                    Spacer(Modifier.height(4.dp))
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
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 36.dp),
                        )
                    }

                    ProviderQualityRow(
                        providerName = providerLabel,
                        qualityLabel = qualityLabel,
                        syncWarning = now.syncWarning,
                        hasDetails = detailLines.isNotEmpty(),
                        detailsExpanded = showAudioDetails,
                        onToggleDetails = { showAudioDetails = !showAudioDetails },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    actualSourceLabel?.let { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    now.error?.let { raw ->
                        Text(
                            friendlyPlaybackMessage(raw),
                            color = scheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { container.player.retryPlayback() }) { Text("Retry") }
                            if (!now.resolved?.fallbacks.isNullOrEmpty()) {
                                TextButton(onClick = { container.player.tryAnotherSource() }) {
                                    Text("Try another source")
                                }
                            }
                            TextButton(onClick = { showErrorDiagnostics = !showErrorDiagnostics }) {
                                Text(if (showErrorDiagnostics) "Hide diagnostics" else "Diagnostics")
                            }
                        }
                    }
                }

                availability?.takeIf {
                    it.status == TrackAvailability.CACHED ||
                        it.status == TrackAvailability.DOWNLOADING ||
                        it.status == TrackAvailability.FAILED ||
                        it.status == TrackAvailability.UNAVAILABLE
                }?.let { info ->
                    Text(
                        buildString {
                            append(info.label)
                            if (info.isSpotifyViaYouTubeMatch) {
                                append(" · Spotify identity kept")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (info.status == TrackAvailability.FAILED ||
                            info.status == TrackAvailability.UNAVAILABLE
                        ) {
                            scheme.error
                        } else {
                            scheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (canCorrectMatch && track != null) {
                    TextButton(
                        onClick = { showMatchCorrectionDialog = true },
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text("Change YouTube match")
                    }
                }

                if (showAudioDetails && detailLines.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    detailLines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }

                if (now.error != null && showErrorDiagnostics) {
                    Text(
                        now.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                if (showSpotifyOutput) {
                    Spacer(Modifier.height(16.dp))
                    Text("Spotify output", style = MaterialTheme.typography.titleSmall)
                    Text(
                        settings.spotifyPlaybackDeviceName?.let { "Playing through: $it" }
                            ?: "Pick a Connect device. Sound comes from that device, not from Kainos.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
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
                                            it.copy(
                                                spotifyPlaybackDeviceId = null,
                                                spotifyPlaybackDeviceName = null,
                                            )
                                        }
                                        spotifyDeviceNotice =
                                            "Previous device went offline. Select another below."
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
                                            spotifyDeviceNotice =
                                                "Spotify will play on ${device.name}. Press play again."
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (failure: Exception) {
                                            spotifyDeviceNotice =
                                                failure.message ?: "Could not save device."
                                        } finally {
                                            spotifyDeviceBusy = false
                                        }
                                    }
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = selected,
                                onClick = null,
                                enabled = !device.isRestricted,
                            )
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
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    spotifyDeviceNotice?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
            }
        }

        if (showSleepTimerDialog) {
            SleepTimerDialog(
                active = sleepTimer.active,
                remainingMs = sleepTimer.remainingMs,
                endOfTrack = sleepTimer.mode is SleepTimerMode.EndOfTrack,
                canEndOfTrack = track != null,
                onDismiss = { showSleepTimerDialog = false },
                onDuration = { ms ->
                    container.sleepTimer.startDuration(ms)
                    showSleepTimerDialog = false
                },
                onEndOfTrack = {
                    container.sleepTimer.startEndOfTrack()
                    showSleepTimerDialog = false
                },
                onCancel = {
                    container.sleepTimer.cancel()
                    showSleepTimerDialog = false
                },
            )
        }

        if (showMatchCorrectionDialog && track != null) {
            YouTubeMatchCorrectionDialog(
                track = track,
                currentVideoId = availability?.youtubeVideoId,
                onDismiss = { showMatchCorrectionDialog = false },
                onSelect = { videoId ->
                    container.setYouTubeMatchOverride(track, videoId)
                    showMatchCorrectionDialog = false
                },
                onClear = {
                    container.clearYouTubeMatchOverride(track)
                    showMatchCorrectionDialog = false
                },
                searchCandidates = { query ->
                    container.searchYouTubeMatchCandidates(track, query)
                },
            )
        }
    }
}

@Composable
private fun YouTubeMatchCorrectionDialog(
    track: Track,
    currentVideoId: String?,
    onDismiss: () -> Unit,
    onSelect: (youtubeVideoId: String) -> Unit,
    onClear: () -> Unit,
    searchCandidates: suspend (query: String?) -> List<Track>,
) {
    var candidates by remember { mutableStateOf<List<Track>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(track.canonicalId) {
        loading = true
        error = null
        runCatching { searchCandidates(null) }
            .onSuccess { candidates = it }
            .onFailure { error = it.message ?: "Search failed" }
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change YouTube match") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Keeps the Spotify identity and heart. Only the offline YouTube audio file changes. Spotify DRM is never downloaded.",
                    style = MaterialTheme.typography.bodySmall,
                )
                currentVideoId?.let {
                    Text("Current video: $it", style = MaterialTheme.typography.labelMedium)
                }
                when {
                    loading -> Text("Searching…", style = MaterialTheme.typography.bodySmall)
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    candidates.isEmpty() -> Text("No candidates found.", style = MaterialTheme.typography.bodySmall)
                    else -> candidates.take(8).forEach { candidate ->
                        val videoId = candidate.sources
                            .firstOrNull { it.provider == ProviderId.YOUTUBE_MUSIC }
                            ?.providerTrackId
                            ?: candidate.canonicalId.removePrefix("yt:").takeIf {
                                candidate.canonicalId.startsWith("yt:")
                            }
                        if (videoId != null) {
                            TextButton(
                                onClick = { onSelect(videoId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    "${candidate.title} · ${candidate.artistLine}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        loading = true
                        runCatching { searchCandidates(null) }
                            .onSuccess { candidates = it }
                            .onFailure { error = it.message ?: "Search failed" }
                        loading = false
                    }
                },
            ) { Text("Refresh") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Clear match") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SleepTimerDialog(
    active: Boolean,
    remainingMs: Long?,
    endOfTrack: Boolean,
    canEndOfTrack: Boolean,
    onDismiss: () -> Unit,
    onDuration: (Long) -> Unit,
    onEndOfTrack: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (active) "Sleep timer" else "Set sleep timer") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (active) {
                    val status = when {
                        endOfTrack -> "Pauses at the end of this track."
                        remainingMs != null -> "Remaining ${formatSleepTimerRemaining(remainingMs)}."
                        else -> "Timer is active."
                    }
                    Text(status, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                SleepTimerDurationPresetsMs.forEach { ms ->
                    val minutes = (ms / 60_000L).toInt()
                    TextButton(
                        onClick = { onDuration(ms) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "$minutes min",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start,
                        )
                    }
                }
                TextButton(
                    onClick = onEndOfTrack,
                    enabled = canEndOfTrack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "End of current track",
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start,
                    )
                }
            }
        },
        confirmButton = {
            if (active) {
                TextButton(onClick = onCancel) { Text("Cancel timer") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun ColumnScope.NowPlayingArtwork(
    title: String?,
    artwork: com.universalmusic.player.domain.model.Artwork?,
    artMax: Dp,
    expand: Boolean,
    compact: Boolean,
) {
    if (expand) {
        // Takes whatever height the controls leave, so they never fall below the first screen.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f, fill = true)
                .heightIn(min = 120.dp),
            contentAlignment = Alignment.Center,
        ) {
            val widthShare = if (compact) maxWidth else maxWidth * 0.92f
            NowPlayingArtworkFrame(
                title = title,
                artwork = artwork,
                modifier = Modifier.size(minOf(widthShare, maxHeight, artMax)),
            )
        }
    } else {
        NowPlayingArtworkFrame(
            title = title,
            artwork = artwork,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .widthIn(max = artMax)
                .fillMaxWidth()
                .aspectRatio(1f),
        )
    }
}

@Composable
private fun NowPlayingArtworkFrame(
    title: String?,
    artwork: com.universalmusic.player.domain.model.Artwork?,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = NowPlayingArtCorner,
        tonalElevation = 0.dp,
        shadowElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        ArtworkImage(
            artwork = artwork,
            contentDescription = title ?: "Artwork",
            modifier = Modifier.fillMaxSize(),
            seed = title ?: "U",
            shape = NowPlayingArtCorner,
        )
    }
}

@Composable
private fun ProviderQualityRow(
    providerName: String?,
    qualityLabel: String?,
    syncWarning: String?,
    hasDetails: Boolean,
    detailsExpanded: Boolean,
    onToggleDetails: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val spine = listOfNotNull(providerName, qualityLabel).joinToString(" · ").ifBlank { "Kainos" }
            Text(
                spine,
                style = MaterialTheme.typography.labelLarge,
                color = providerName?.let(::providerColor) ?: scheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (hasDetails) {
                TextButton(onClick = onToggleDetails) {
                    Text(if (detailsExpanded) "Hide details" else "Details")
                }
            }
        }
        syncWarning?.let { warning ->
            Text(
                warning,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Soft wash tint from artwork/track identity, blended toward the active scheme surface
 * so Appearance light/dark schemes stay in charge.
 */
private fun artworkWashColor(
    seed: String?,
    providerName: String?,
    surface: Color,
    primary: Color,
): Color {
    val accent = when {
        providerName != null -> providerColor(providerName)
        seed != null -> {
            val hash = seed.hashCode()
            Color(
                red = (90 + ((hash ushr 16) and 0x5F)) / 255f,
                green = (80 + ((hash ushr 8) and 0x6F)) / 255f,
                blue = (70 + (hash and 0x5F)) / 255f,
            )
        }
        else -> primary
    }
    return Color(
        red = accent.red * 0.35f + surface.red * 0.65f,
        green = accent.green * 0.35f + surface.green * 0.65f,
        blue = accent.blue * 0.35f + surface.blue * 0.65f,
        alpha = 1f,
    )
}

private fun Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
