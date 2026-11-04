package com.universalmusic.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.app.UiRequest
import com.universalmusic.player.app.ensureAppContainer
import com.universalmusic.player.domain.failure.AppFailure
import com.universalmusic.player.domain.failure.AppFailures
import com.universalmusic.player.domain.failure.shown
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.HandoffNotice
import com.universalmusic.player.domain.playback.NowPlayingState
import com.universalmusic.player.ui.components.KainosRail
import com.universalmusic.player.ui.components.KainosTabBar
import com.universalmusic.player.ui.components.MiniPlayerBar
import com.universalmusic.player.ui.components.skinGround
import com.universalmusic.player.ui.theme.AccentSlot
import com.universalmusic.player.ui.theme.AccentUse
import com.universalmusic.player.ui.theme.LocalKainosPalette
import com.universalmusic.player.ui.theme.LocalSkinTokens
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.graphics.Color
import com.universalmusic.player.ui.navigation.AppDestination
import com.universalmusic.player.ui.screens.HomeScreen
import com.universalmusic.player.ui.screens.LibraryPresenter
import com.universalmusic.player.ui.screens.LibraryScreen
import com.universalmusic.player.ui.screens.NowPlayingScreen
import com.universalmusic.player.ui.screens.QueueScreen
import com.universalmusic.player.ui.screens.SearchScreen
import com.universalmusic.player.ui.screens.SearchPresenter
import com.universalmusic.player.ui.screens.TrackActionsPresenter
import com.universalmusic.player.ui.screens.SettingsScreen
import com.universalmusic.player.ui.screens.SettingsTarget
import com.universalmusic.player.ui.theme.KainosTheme
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.State
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import coil3.compose.LocalPlatformContext
import com.universalmusic.player.ui.components.warmQueueArtwork
import com.universalmusic.player.domain.model.ProviderId

@Composable
fun UniversalMusicApp(container: AppContainer = ensureAppContainer()) {
    val settings by container.settings.collectAsState()
    KainosTheme(settings) {
        CompositionLocalProvider(LocalTextInputFocus provides container::setTextInputFocused) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                AppScaffold(
                    container = container,
                    layoutBand = desktopLayoutBand(maxWidth),
                    compactPlayer = settings.compactMode,
                )
            }
        }
    }
}

@Composable
private fun AppScaffold(
    container: AppContainer,
    layoutBand: DesktopLayoutBand,
    compactPlayer: Boolean,
) {
    val desktop = layoutBand != DesktopLayoutBand.Narrow
    val wide = layoutBand == DesktopLayoutBand.Wide

    var destinationName by rememberSaveable { mutableStateOf(AppDestination.Home.name) }
    var tabBackStackNames by rememberSaveable { mutableStateOf(listOf<String>()) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    var settingsTarget by remember { mutableStateOf<SettingsTarget?>(null) }
    var nowPlayingPaneExpanded by rememberNowPlayingPaneExpanded(compactPlayer)
    var nowPlayingPaneWidthDp by rememberSaveable {
        mutableFloatStateOf(DesktopNowPlayingPaneDefaultWidth.value)
    }
    val destination = remember(destinationName) {
        runCatching { AppDestination.valueOf(destinationName) }.getOrDefault(AppDestination.Home)
    }
    val tabStateHolder = rememberSaveableStateHolder()
    val searchScope = rememberCoroutineScope()
    val searchPresenter = remember(container) { SearchPresenter.from(container, searchScope) }
    val libraryPresenter = remember(container) { LibraryPresenter.from(container, searchScope) }
    val trackActions = remember(container) { TrackActionsPresenter.from(container) }
    val currentTrack by playbackChromeTrack(container.player.nowPlaying)
    val queue by container.player.queueState.collectAsState()
    val appReady by container.ready.collectAsState()
    val recentlyPlayed by container.library.recentlyPlayed.collectAsState()
    val remote by container.remoteControl.state.collectAsState()
    val nothingEverPlayed = appReady && recentlyPlayed.isEmpty() && currentTrack == null
    val imageContext = LocalPlatformContext.current
    LaunchedEffect(queue, imageContext) {
        container.player.nowPlaying.map { it.isPlaying }.distinctUntilChanged().collectLatest { playing ->
            if (!playing) return@collectLatest
            delay(1_200)
            val neighbours = listOfNotNull(queue.history.lastOrNull(), queue.upcoming.firstOrNull())
            for (item in neighbours) {
                if (item.track.sources.any { it.provider != ProviderId.LOCAL && it.provider != ProviderId.YOUTUBE_MUSIC }) continue
                try {
                    val art = item.track.artwork ?: container.local.resolveEmbeddedArtwork(item.track)
                    if (art != null) warmQueueArtwork(imageContext, art)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // A cover failure must not affect transport; normal display can retry later.
                }
            }
        }
    }
    val canSkipNext = run {
        queue.shuffle
        queue.repeat
        queue.items.size
        queue.currentIndex
        container.player.canSkipNext()
    }

    // Side pane vs overlay: crossing the narrow breakpoint must not cover the content pane
    // or wipe tab SaveableState. Collapse overlays when entering narrow; expand the side
    // pane when entering desktop if the user just opened Now Playing from play.
    var previousBand by remember { mutableStateOf(layoutBand) }
    LaunchedEffect(layoutBand) {
        val wasNarrow = previousBand == DesktopLayoutBand.Narrow
        val isNarrow = layoutBand == DesktopLayoutBand.Narrow
        when {
            !wasNarrow && isNarrow -> {
                showNowPlaying = false
                showQueue = false
            }
            wasNarrow && !isNarrow -> {
                if (showNowPlaying) {
                    nowPlayingPaneExpanded = true
                }
            }
        }
        previousBand = layoutBand
    }

    // On wide windows Queue sits beside content; on standard width it replaces the NP pane.
    val showWideQueueColumn = desktop && showQueue && wide
    val queueReplacesSidePane = desktop && showQueue && !wide
    val showSideNowPlaying = desktop && !queueReplacesSidePane && nowPlayingPaneShown(nowPlayingPaneExpanded, nothingEverPlayed, remote.available, showNowPlaying)
    val showDesktopMiniBar = desktop &&
        currentTrack != null &&
        !showSideNowPlaying &&
        !queueReplacesSidePane

    fun dismissOverlayStack(): Boolean = when {
        showQueue -> {
            showQueue = false
            true
        }
        !desktop && showNowPlaying -> {
            showNowPlaying = false
            true
        }
        desktop && nowPlayingPaneExpanded && (compactPlayer || showNowPlaying) -> {
            nowPlayingPaneExpanded = false
            showNowPlaying = false
            true
        }
        else -> false
    }

    fun navigateToTab(next: AppDestination) {
        if (!desktop) {
            showNowPlaying = false
            showQueue = false
        }
        if (next.name == destinationName) return
        if (next == AppDestination.Home) {
            // Home is the root: clear history so the next back can leave the app.
            tabBackStackNames = emptyList()
            destinationName = AppDestination.Home.name
            return
        }
        tabBackStackNames = tabBackStackNames + destinationName
        destinationName = next.name
    }

    fun handleSystemBack(): Boolean {
        if (dismissOverlayStack()) return true
        if (tabBackStackNames.isNotEmpty()) {
            destinationName = tabBackStackNames.last()
            tabBackStackNames = tabBackStackNames.dropLast(1)
            return true
        }
        return false
    }

    fun openSettings(target: SettingsTarget) {
        settingsTarget = target
        navigateToTab(AppDestination.Settings)
    }

    fun openNowPlaying() {
        if (desktop) {
            nowPlayingPaneExpanded = true
            showNowPlaying = true
            if (!wide) showQueue = false
        } else {
            showQueue = false
            showNowPlaying = true
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, offlineOnly: Boolean = false) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        container.library.recordPlay(tracks[index])
        container.playTracks(tracks, startIndex = index, offlineOnly = offlineOnly)
        openNowPlaying()
    }

    fun playSearchTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        container.library.recordPlay(tracks[index])
        container.playSearchResults(tracks, startIndex = index)
        openNowPlaying()
    }

    // Queue → Now Playing → previous tab → … → Home. At root Home, let the system leave the app.
    PlatformBackHandler(
        enabled = showQueue ||
            showNowPlaying ||
            (desktop && nowPlayingPaneExpanded && compactPlayer) ||
            tabBackStackNames.isNotEmpty(),
    ) {
        handleSystemBack()
    }

    // The arrival mark belongs to one visit; leaving Settings lets the same spot be opened again.
    LaunchedEffect(destination) {
        if (destination != AppDestination.Settings) settingsTarget = null
    }

    val handleUiRequest by rememberUpdatedState<(UiRequest) -> Unit> { request ->
        when (request) {
            UiRequest.FOCUS_SEARCH -> navigateToTab(AppDestination.Search)
            UiRequest.TOGGLE_QUEUE -> showQueue = !showQueue
            UiRequest.DISMISS_OVERLAY -> handleSystemBack()
            UiRequest.OPEN_NOW_PLAYING -> openNowPlaying()
            UiRequest.FOCUS_LIBRARY_SEARCH -> {
                showQueue = false
                navigateToTab(AppDestination.Library)
            }
        }
    }
    LaunchedEffect(container) {
        container.uiRequests.collect { request -> handleUiRequest(request) }
    }

    Scaffold(
        modifier = Modifier.skinGround(),
        containerColor = Color.Transparent,
        // A transparent container has no content colour of its own; text must still get the ink.
        contentColor = MaterialTheme.colorScheme.onBackground,
        contentWindowInsets = if (!desktop && showNowPlaying && !showQueue) {
            WindowInsets(0, 0, 0, 0)
        } else {
            ScaffoldDefaults.contentWindowInsets
        },
        bottomBar = {
            if (!desktop && !showNowPlaying && !showQueue) {
                Column {
                    currentTrack?.let { track ->
                        DesktopMiniPlayer(
                            container = container,
                            track = track,
                            canSkipNext = canSkipNext,
                            onOpen = { openNowPlaying() },
                        )
                    }
                    KainosTabBar(destination, ::navigateToTab)
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            if (desktop) {
                KainosRail(
                    selected = destination,
                    onSelect = ::navigateToTab,
                    footer = {
                        if (!showSideNowPlaying && nowPlayingPaneShown(true, nothingEverPlayed, remote.available, showNowPlaying)) {
                            IconButton(onClick = { openNowPlaying() }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                    contentDescription = "Show now playing",
                                )
                            }
                        }
                    },
                )
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }

            // Main content column: tabs stay composed across resize so Library scroll survives.
            Column(Modifier.weight(1f).fillMaxSize()) {
                FailureBar(container.failures)
                HandoffNoticeBar(container)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Box(Modifier.weight(1f).fillMaxSize()) {
                        when {
                            showQueue && !desktop -> QueueScreen(container) { showQueue = false }
                            showNowPlaying && !desktop -> NowPlayingScreen(
                                container,
                                onOpenQueue = { showQueue = true },
                                onClose = { showNowPlaying = false },
                            )
                            else -> tabStateHolder.SaveableStateProvider(destination.name) {
                                when (destination) {
                                    AppDestination.Home -> HomeScreen(
                                        container,
                                        onPlayTracks = ::playTracks,
                                        onOpenNowPlaying = { openNowPlaying() },
                                        onOpenSettings = ::openSettings,
                                        onOpenSearch = { navigateToTab(AppDestination.Search) },
                                        onOpenLibrary = { navigateToTab(AppDestination.Library) },
                                        trackActions = trackActions,
                                        // The side pane already shows the session, so Home skips its session card.
                                        playerPaneVisible = showSideNowPlaying,
                                    )
                                    AppDestination.Search -> SearchScreen(
                                        presenter = searchPresenter,
                                        onPlayTrackInList = ::playSearchTracks,
                                        trackActions = trackActions,
                                        requestFocus = true,
                                        onOpenSettings = ::openSettings,
                                    )
                                    AppDestination.Library -> LibraryScreen(
                                        container.libraryTypeAhead,
                                        container::consumeLibraryTypeAhead,
                                        libraryPresenter,
                                        ::playTracks,
                                        trackActions,
                                        onPlayOffline = { tracks, index -> playTracks(tracks, index, offlineOnly = true) },
                                    )
                                    AppDestination.Settings -> SettingsScreen(container, settingsTarget)
                                }
                            }
                        }
                        // Overlays the list instead of pushing it, so rapid row actions never shift rows.
                        TrackActionNoticeBar(trackActions, Modifier.align(Alignment.BottomCenter))
                    }

                    if (showWideQueueColumn) {
                        Box(
                            Modifier
                                .fillMaxHeight()
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant),
                        )
                        Surface(
                            Modifier
                                .width(DesktopQueueBesideWidth)
                                .fillMaxHeight(),
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            tonalElevation = 0.dp,
                            shadowElevation = 0.dp,
                        ) {
                            QueueScreen(container) { showQueue = false }
                        }
                    }
                }

                if (showDesktopMiniBar) {
                    currentTrack?.let { track ->
                        DesktopMiniPlayer(
                            container = container,
                            track = track,
                            canSkipNext = canSkipNext,
                            onOpen = { openNowPlaying() },
                        )
                    }
                }
            }

            if (queueReplacesSidePane) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Surface(
                    Modifier
                        .width(
                            nowPlayingPaneWidthDp.dp.coerceIn(
                                DesktopNowPlayingPaneMinWidth,
                                DesktopNowPlayingPaneMaxWidth,
                            ),
                        )
                        .fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    QueueScreen(container) { showQueue = false }
                }
            } else if (showSideNowPlaying) {
                DesktopResizeHandle(
                    onDragWidthDelta = { delta ->
                        nowPlayingPaneWidthDp = (nowPlayingPaneWidthDp + delta.value)
                            .coerceIn(
                                DesktopNowPlayingPaneMinWidth.value,
                                DesktopNowPlayingPaneMaxWidth.value,
                            )
                    },
                )
                Surface(
                    Modifier
                        .width(
                            nowPlayingPaneWidthDp.dp.coerceIn(
                                DesktopNowPlayingPaneMinWidth,
                                DesktopNowPlayingPaneMaxWidth,
                            ),
                        )
                        .fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = {
                                nowPlayingPaneExpanded = false
                                showNowPlaying = false
                            }) {
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = "Hide now playing",
                                )
                            }
                            Text(
                                "Now playing",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            NowPlayingScreen(
                                container,
                                onOpenQueue = { showQueue = true },
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HandoffNoticeBar(container: AppContainer) {
    val handoff = container.listeningHandoff
    val notice by handoff.notice.collectAsState()
    var shown by remember { mutableStateOf<HandoffNotice?>(null) }
    notice?.let { shown = it }
    LaunchedEffect(notice?.id) {
        val id = notice?.id ?: return@LaunchedEffect
        delay(HandoffNoticeVisibleMs)
        handoff.dismissNotice(id)
    }
    TopNoticeSlot(visible = notice != null) {
        shown?.let { current ->
            HandoffNoticePill(current.deviceName) { handoff.dismissNotice(current.id) }
        }
    }
}

@Composable
private fun TrackActionNoticeBar(actions: TrackActionsPresenter, modifier: Modifier = Modifier) {
    val notice by actions.notice.collectAsState()
    var shown by remember { mutableStateOf(notice) }
    notice?.let { shown = it }
    LaunchedEffect(notice?.id) {
        val id = notice?.id ?: return@LaunchedEffect
        delay(TrackActionNoticeVisibleMs)
        actions.dismissNotice(id)
    }
    AnimatedVisibility(
        visible = notice != null,
        modifier = modifier.padding(16.dp),
        enter = fadeIn(tween(HandoffNoticeEnterMs, easing = StandardEasing)) +
            slideInVertically(tween(HandoffNoticeEnterMs, easing = StandardEasing)) { it / 2 },
        exit = fadeOut(tween(HandoffNoticeExitMs, easing = StandardEasing)),
    ) {
        shown?.let { current -> TrackActionNoticePill(current.message) }
    }
}

/** Confirms a track menu action; quieter than a failure and gone on its own. */
@Composable
internal fun TrackActionNoticePill(message: String) {
    Surface(
        shape = LocalSkinTokens.current.rounding.full,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, LocalKainosPalette.current.hair),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 2.dp,
        modifier = Modifier
            .widthIn(max = 480.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            message,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
        )
    }
}

@Composable
internal fun FailureBar(failures: StateFlow<Set<AppFailure>>) {
    val failure = failures.collectAsState().value.shown
    var last by remember { mutableStateOf<AppFailure?>(null) }
    failure?.let { last = it }
    LaunchedEffect(failure) { AppFailures.holdOnScreen(failure) }
    TopNoticeSlot(visible = failure != null) {
        last?.let { FailureNotice(it.message, onDismiss = AppFailures::dismiss) }
    }
}

@Composable
internal fun FailureNotice(message: String, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(LocalSkinTokens.current.rounding.large),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, LocalKainosPalette.current.hair),
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = 2.dp,
        modifier = Modifier
            .widthIn(max = 480.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp).weight(1f, fill = false),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

@Composable
private fun TopNoticeSlot(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxWidth(),
        enter = expandVertically(tween(HandoffNoticeEnterMs, easing = StandardEasing), expandFrom = Alignment.Top) +
            fadeIn(tween(HandoffNoticeEnterMs, easing = StandardEasing)),
        exit = shrinkVertically(tween(HandoffNoticeExitMs, easing = StandardEasing), shrinkTowards = Alignment.Top) +
            fadeOut(tween(HandoffNoticeExitMs, easing = StandardEasing)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                // Now Playing draws under the status bar, so the pill must inset itself there.
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

@Composable
internal fun HandoffNoticePill(deviceName: String, onDismiss: () -> Unit) {
    Surface(
        shape = LocalSkinTokens.current.rounding.full,
        color = MaterialTheme.colorScheme.secondaryContainer,
        border = BorderStroke(1.dp, LocalKainosPalette.current.hair),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shadowElevation = 2.dp,
        modifier = Modifier
            .widthIn(max = 480.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(
            Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.Devices,
                contentDescription = null,
                tint = LocalKainosPalette.current.accent(AccentSlot.Blue, AccentUse.Mark, MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.size(20.dp),
            )
            Text(
                "Continued from $deviceName",
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 12.dp).weight(1f, fill = false),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Dismiss")
            }
        }
    }
}

private const val HandoffNoticeVisibleMs = 6_000L
private const val TrackActionNoticeVisibleMs = 2_500L
private const val HandoffNoticeEnterMs = 300
private const val HandoffNoticeExitMs = 200
private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
private fun DesktopMiniPlayer(
    container: AppContainer,
    track: Track,
    canSkipNext: Boolean,
    onOpen: () -> Unit,
) {
    val now by container.player.nowPlaying.collectAsState()
    val durationMs = now.durationMs?.takeIf { it > 0 }
        ?: track.durationMs?.takeIf { it > 0 }
    val miniProgress = if (durationMs != null) {
        (now.positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    MiniPlayerBar(
        title = track.title,
        artist = track.artistLine,
        artwork = track.artwork,
        isPlaying = now.isPlaying,
        providerLabel = now.resolved?.source?.provider?.displayName,
        canSkipNext = canSkipNext,
        progress = miniProgress,
        buffering = now.buffering,
        durationMs = durationMs,
        onOpen = onOpen,
        onToggle = { container.player.togglePlayPause() },
        onNext = { container.player.skipToNext() },
    )
}

/** The app chrome only depends on track identity/metadata, never transport position. */
@Composable
internal fun playbackChromeTrack(nowPlaying: StateFlow<NowPlayingState>): State<Track?> =
    remember(nowPlaying) { nowPlaying.map { it.track }.distinctUntilChanged() }
        .collectAsState(initial = nowPlaying.value.track)
