package com.universalmusic.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
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
import com.universalmusic.player.ui.screens.LibraryScreen
import com.universalmusic.player.ui.screens.NowPlayingScreen
import com.universalmusic.player.ui.screens.QueueScreen
import com.universalmusic.player.ui.screens.SearchScreen
import com.universalmusic.player.ui.screens.SearchPresenter
import com.universalmusic.player.ui.screens.SettingsScreen
import com.universalmusic.player.ui.theme.KainosTheme
import kotlinx.coroutines.delay

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
    val now by container.player.nowPlaying.collectAsState()
    val queue by container.player.queueState.collectAsState()
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
                    showNowPlaying = false
                }
            }
        }
        previousBand = layoutBand
    }

    // On wide windows Queue sits beside content; on standard width it replaces the NP pane.
    val showWideQueueColumn = desktop && showQueue && wide
    val queueReplacesSidePane = desktop && showQueue && !wide
    val showSideNowPlaying = desktop && nowPlayingPaneExpanded && !queueReplacesSidePane
    val showDesktopMiniBar = desktop &&
        now.track != null &&
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

    fun openNowPlaying() {
        if (desktop) {
            nowPlayingPaneExpanded = true
            showNowPlaying = false
            if (!wide) showQueue = false
        } else {
            showQueue = false
            showNowPlaying = true
        }
    }

    fun playTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        container.library.recordPlay(tracks[index])
        container.playTracks(tracks, startIndex = index)
        openNowPlaying()
    }

    fun playSearchTracks(tracks: List<Track>, startIndex: Int = 0, query: String) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        container.library.recordPlay(tracks[index])
        container.playSearchResults(tracks, startIndex = index, query = query)
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
                    now.track?.let { track ->
                        DesktopMiniPlayer(
                            container = container,
                            track = track,
                            now = now,
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
                        if (!nowPlayingPaneExpanded) {
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
                                        onOpenSettings = { navigateToTab(AppDestination.Settings) },
                                        onOpenSearch = { navigateToTab(AppDestination.Search) },
                                        onOpenLibrary = { navigateToTab(AppDestination.Library) },
                                        // The side pane already shows the session, so Home skips its session card.
                                        playerPaneVisible = showSideNowPlaying,
                                    )
                                    AppDestination.Search -> SearchScreen(
                                        presenter = searchPresenter,
                                        onPlayTrackInList = { tracks, index, query ->
                                            playSearchTracks(tracks, index, query)
                                        },
                                        requestFocus = true,
                                    )
                                    AppDestination.Library -> LibraryScreen(container, ::playTracks)
                                    AppDestination.Settings -> SettingsScreen(container)
                                }
                            }
                        }
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
                    now.track?.let { track ->
                        DesktopMiniPlayer(
                            container = container,
                            track = track,
                            now = now,
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
    AnimatedVisibility(
        visible = notice != null,
        modifier = Modifier.fillMaxWidth(),
        enter = expandVertically(tween(HandoffNoticeEnterMs, easing = StandardEasing)) +
            fadeIn(tween(HandoffNoticeEnterMs, easing = StandardEasing)),
        exit = shrinkVertically(tween(HandoffNoticeExitMs, easing = StandardEasing)) +
            fadeOut(tween(HandoffNoticeExitMs, easing = StandardEasing)),
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            shown?.let { current ->
                HandoffNoticePill(current.deviceName) { handoff.dismissNotice(current.id) }
            }
        }
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
private const val HandoffNoticeEnterMs = 300
private const val HandoffNoticeExitMs = 200
private val StandardEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

@Composable
private fun DesktopMiniPlayer(
    container: AppContainer,
    track: Track,
    now: NowPlayingState,
    canSkipNext: Boolean,
    onOpen: () -> Unit,
) {
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
