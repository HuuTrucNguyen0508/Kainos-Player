package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.domain.playback.SleepTimerMode
import com.universalmusic.player.domain.playback.friendlyPlaybackMessage
import com.universalmusic.player.ui.components.albumLight
import com.universalmusic.player.ui.components.isDark
import com.universalmusic.player.ui.components.rememberAlbumLight

@Composable
fun NowPlayingScreen(
    container: AppContainer,
    onOpenQueue: () -> Unit,
    compact: Boolean = false,
    onClose: (() -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    val presenter = remember(container) { NowPlayingPresenter.from(container, scope) }
    val ui by presenter.state.collectAsState()
    val track = ui.now.track
    // Identity stays fixed across metadata/artwork refresh for the same queue entry.
    val trackIdentity = ui.trackIdentity
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showMatchCorrectionDialog by remember { mutableStateOf(false) }
    var showAudioDetails by remember(trackIdentity) { mutableStateOf(false) }
    var showErrorDiagnostics by remember(ui.now.error) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    // Signature: the cover's own color glows behind the artwork (scheme accent when there is none).
    val albumLight by rememberAlbumLight(track?.artwork?.url)
    // The glow is centred on wherever the cover actually sits (phone, desktop pane, scrolled details).
    var screenBounds by remember { mutableStateOf<Rect?>(null) }
    var artBounds by remember { mutableStateOf<Rect?>(null) }
    val lightCenter = albumLightCenter(screenBounds, artBounds)
    val horizontalPad = if (compact) 16.dp else 20.dp
    val availability = visibleAvailability(ui.availability)

    Box(
        Modifier
            .fillMaxSize()
            .background(scheme.background)
            .onGloballyPositioned { screenBounds = it.boundsInRoot() }
            .albumLight(albumLight, darkScheme = scheme.isDark, centerX = lightCenter.x, centerY = lightCenter.y)
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
                compact -> 340.dp
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
                            onPositioned = { artBounds = it },
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
                    if (ui.now.buffering) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            "Loading audio…",
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    NowPlayingProgress(
                        nowPlaying = presenter.nowPlaying,
                        onSeek = presenter::seekTo,
                    )

                    Spacer(Modifier.height(8.dp))
                    NowPlayingTransport(
                        track = track,
                        isPlaying = ui.now.isPlaying,
                        buffering = ui.now.buffering,
                        favorite = ui.now.favorite,
                        queue = ui.queue,
                        repeat = ui.repeat,
                        shuffle = ui.shuffle,
                        sleepActive = ui.sleepTimer.active,
                        sleepRemainingLabel = ui.sleepRemainingLabel,
                        scheme = scheme,
                        onPrevious = presenter::skipToPrevious,
                        onTogglePlayPause = presenter::togglePlayPause,
                        onNext = presenter::skipToNext,
                        canSkipNext = presenter::canSkipNext,
                        onToggleFavorite = { track?.let(presenter::toggleFavorite) },
                        onCycleRepeat = presenter::cycleRepeat,
                        onToggleShuffle = presenter::toggleShuffle,
                        onOpenQueue = onOpenQueue,
                        onOpenSleepTimer = { showSleepTimerDialog = true },
                    )

                    Spacer(Modifier.height(4.dp))
                    NowPlayingVolume(
                        volume = presenter.volume,
                        scheme = scheme,
                        onDragVolume = presenter::setVolume,
                        onCommitVolume = presenter::setPlaybackVolume,
                    )

                    ProviderQualityRow(
                        // Before a restored track resolves, name its own source instead of nothing.
                        providerName = ui.providerName,
                        qualityLabel = ui.qualityLabel,
                        syncWarning = ui.now.syncWarning,
                        hasDetails = ui.detailLines.isNotEmpty(),
                        detailsExpanded = showAudioDetails,
                        onToggleDetails = { showAudioDetails = !showAudioDetails },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    ui.actualSourceLabel?.let { label ->
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    ui.now.error?.let { raw ->
                        NowPlayingErrorBlock(
                            message = friendlyPlaybackMessage(raw),
                            showDiagnostics = showErrorDiagnostics,
                            hasFallbacks = !ui.now.resolved?.fallbacks.isNullOrEmpty(),
                            scheme = scheme,
                            onRetry = presenter::retryPlayback,
                            onTryAnother = presenter::tryAnotherSource,
                            onToggleDiagnostics = { showErrorDiagnostics = !showErrorDiagnostics },
                        )
                    }
                }

                availability?.let { info ->
                    NowPlayingAvailabilityLine(
                        info = info,
                        scheme = scheme,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (ui.canCorrectMatch && track != null) {
                    TextButton(
                        onClick = { showMatchCorrectionDialog = true },
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text("Change YouTube match")
                    }
                }

                if (showAudioDetails && ui.detailLines.isNotEmpty()) {
                    NowPlayingDetailLines(ui.detailLines, scheme)
                }

                if (ui.now.error != null && showErrorDiagnostics) {
                    Text(
                        ui.now.error.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                NowPlayingSpotifyOutput(
                    visible = ui.showSpotifyOutput,
                    scope = scope,
                    settings = presenter.settings,
                    scheme = scheme,
                    getConnectDevices = container.spotify::getConnectDevices,
                    updateSettings = container::updateSettings,
                )

                Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
            }
        }

        if (showSleepTimerDialog) {
            SleepTimerDialog(
                active = ui.sleepTimer.active,
                remainingMs = ui.sleepTimer.remainingMs,
                endOfTrack = ui.sleepTimer.mode is SleepTimerMode.EndOfTrack,
                canEndOfTrack = track != null,
                onDismiss = { showSleepTimerDialog = false },
                onDuration = { ms ->
                    presenter.startSleepDuration(ms)
                    showSleepTimerDialog = false
                },
                onEndOfTrack = {
                    presenter.startSleepEndOfTrack()
                    showSleepTimerDialog = false
                },
                onCancel = {
                    presenter.cancelSleepTimer()
                    showSleepTimerDialog = false
                },
            )
        }

        if (showMatchCorrectionDialog && track != null) {
            YouTubeMatchCorrectionDialog(
                track = track,
                currentVideoId = ui.availability?.youtubeVideoId,
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

private fun albumLightCenter(screen: Rect?, art: Rect?): Offset {
    if (screen == null || art == null || screen.width <= 0f || screen.height <= 0f) {
        return Offset(0.5f, 0.32f)
    }
    return Offset(
        (art.center.x - screen.left) / screen.width,
        (art.center.y - screen.top) / screen.height,
    )
}
