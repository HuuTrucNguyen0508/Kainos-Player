package com.universalmusic.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.app.UiRequest
import com.universalmusic.player.app.ensureAppContainer
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.NowPlayingState
import com.universalmusic.player.ui.components.MiniPlayerBar
import com.universalmusic.player.ui.navigation.AppDestination
import com.universalmusic.player.ui.screens.HomeScreen
import com.universalmusic.player.ui.screens.LibraryScreen
import com.universalmusic.player.ui.screens.NowPlayingScreen
import com.universalmusic.player.ui.screens.QueueScreen
import com.universalmusic.player.ui.screens.SearchScreen
import com.universalmusic.player.ui.screens.SearchUiState
import com.universalmusic.player.ui.screens.SettingsScreen
import com.universalmusic.player.ui.theme.UniversalMusicTheme

@Composable
fun UniversalMusicApp(container: AppContainer = ensureAppContainer()) {
    val settings by container.settings.collectAsState()
    UniversalMusicTheme(settings.themeMode, settings.colorScheme) {
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
    var nowPlayingPaneExpanded by rememberSaveable { mutableStateOf(true) }
    var nowPlayingPaneWidthDp by rememberSaveable {
        mutableFloatStateOf(DesktopNowPlayingPaneDefaultWidth.value)
    }
    val destination = remember(destinationName) {
        runCatching { AppDestination.valueOf(destinationName) }.getOrDefault(AppDestination.Home)
    }
    val tabStateHolder = rememberSaveableStateHolder()
    val searchUi = remember { SearchUiState() }
    val now by container.player.nowPlaying.collectAsState()
    val queue by container.player.queue.queue.collectAsState()
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

    // Compact mode prefers the mini bar; expanding remains available via Open Now Playing.
    var previousCompactPlayer by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(compactPlayer, desktop) {
        if (!desktop) {
            previousCompactPlayer = compactPlayer
            return@LaunchedEffect
        }
        val was = previousCompactPlayer
        previousCompactPlayer = compactPlayer
        when {
            // First observation while already compact, or newly enabled.
            compactPlayer && (was == null || was == false) -> nowPlayingPaneExpanded = false
        }
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
        if (next == destination) return
        if (next == AppDestination.Home) {
            // Home is the root: clear history so the next back can leave the app.
            tabBackStackNames = emptyList()
            destinationName = AppDestination.Home.name
            return
        }
        tabBackStackNames = tabBackStackNames + destination.name
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

    LaunchedEffect(container) {
        container.uiRequests.collect { request ->
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
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
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
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        tonalElevation = 3.dp,
                    ) {
                        AppDestination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = { navigateToTab(item) },
                                icon = { Icon(item.icon(), contentDescription = item.label) },
                                label = { Text(item.label) },
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (desktop) {
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(88.dp),
                    header = {
                        Column(
                            Modifier.padding(top = 20.dp, bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Kainos",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                ) {
                    AppDestination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item,
                            onClick = { navigateToTab(item) },
                            icon = { Icon(item.icon(), contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                    if (!nowPlayingPaneExpanded) {
                        IconButton(
                            onClick = { openNowPlaying() },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = "Show now playing",
                            )
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxHeight()
                        .width(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }

            // Main content column: tabs stay composed across resize so Library scroll survives.
            Column(Modifier.weight(1f).fillMaxSize()) {
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
                                    )
                                    AppDestination.Search -> SearchScreen(
                                        container,
                                        onPlayTrackInList = { tracks, index, query ->
                                            playSearchTracks(tracks, index, query)
                                        },
                                        state = searchUi,
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
                    color = MaterialTheme.colorScheme.surfaceVariant,
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
                    color = MaterialTheme.colorScheme.surfaceVariant,
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
        onOpen = onOpen,
        onToggle = { container.player.togglePlayPause() },
        onNext = { container.player.skipToNext() },
    )
}

private fun AppDestination.icon() = when (this) {
    AppDestination.Home -> Icons.Default.Home
    AppDestination.Search -> Icons.Default.Search
    AppDestination.Library -> Icons.Default.LibraryMusic
    AppDestination.Settings -> Icons.Default.Settings
}
