package com.universalmusic.player.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.vector.ImageVector
import com.universalmusic.player.data.settings.NowPlayingDock
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import com.universalmusic.player.ui.components.FrostGround
import com.universalmusic.player.ui.components.SkinPane
import com.universalmusic.player.ui.components.isFrost
import kotlin.random.Random
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
                    dockSetting = settings.nowPlayingDock,
                    portrait = desktopPortrait(maxWidth, maxHeight),
                    windowHeight = maxHeight,
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
    dockSetting: NowPlayingDock,
    portrait: Boolean = false,
    windowHeight: Dp = Dp.Unspecified,
) {
    val desktop = layoutBand != DesktopLayoutBand.Narrow
    val wide = layoutBand.isWide
    val ultraWide = layoutBand == DesktopLayoutBand.UltraWide
    val frost = isFrost()
    val dock = resolveDockEdge(dockSetting, portrait)
    val dockDrag = remember { DockDragState() }

    var destinationName by rememberSaveable { mutableStateOf(AppDestination.Home.name) }
    var tabBackStackNames by rememberSaveable { mutableStateOf(listOf<String>()) }
    // A step back inside the open screen (Library's folder trail), taken after overlays and before tab history.
    var screenBack by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showNowPlaying by rememberSaveable { mutableStateOf(false) }
    var showQueue by rememberSaveable { mutableStateOf(false) }
    // The ultrawide Queue column's toggle for this session; null until the listener uses it.
    var queueColumnChoice by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var settingsTarget by remember { mutableStateOf<SettingsTarget?>(null) }
    var nowPlayingPaneExpanded by rememberNowPlayingPaneExpanded(compactPlayer)
    var nowPlayingPaneWidthDp by rememberSaveable {
        mutableFloatStateOf(DesktopNowPlayingPaneDefaultWidth.value)
    }
    // Follows the window's height until the seam is dragged; NaN means "not dragged yet".
    var nowPlayingBandDraggedDp by rememberSaveable { mutableFloatStateOf(Float.NaN) }
    val nowPlayingBandHeight = when {
        !windowHeight.isSpecified -> 360.dp
        nowPlayingBandDraggedDp.isNaN() -> defaultNowPlayingBandHeight(windowHeight)
        else -> clampNowPlayingBandHeight(nowPlayingBandDraggedDp.dp, windowHeight)
    }
    val destination = remember(destinationName) {
        runCatching { AppDestination.valueOf(destinationName) }.getOrDefault(AppDestination.Home)
    }
    val tabStateHolder = rememberSaveableStateHolder()
    val searchScope = rememberCoroutineScope()
    val searchPresenter = remember(container) { SearchPresenter.from(container, searchScope) }
    val libraryPresenter = remember(container) { LibraryPresenter.from(container, searchScope) }
    val trackActions = remember(container) { TrackActionsPresenter.from(container) }
    val searchPlaylist by searchPresenter.playlist.collectAsState()
    val currentTrack by playbackChromeTrack(container.player.nowPlaying)
    val queue by container.player.queueState.collectAsState()
    val appReady by container.ready.collectAsState()
    val recentlyPlayed by container.library.recentlyPlayed.collectAsState()
    val remote by container.remoteControl.state.collectAsState()
    val nothingEverPlayed = appReady && recentlyPlayed.isEmpty() && currentTrack == null
    val queueColumnOpen = queueColumnShown(layoutBand, queueColumnChoice, nothingEverPlayed)
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
        // An open Queue carries into the ultrawide column instead of showing in two places.
        if (layoutBand == DesktopLayoutBand.UltraWide && showQueue) {
            queueColumnChoice = true
            showQueue = false
        }
        previousBand = layoutBand
    }

    // On ultrawide windows Queue is a column of the frame; on wide windows it sits beside content; on
    // standard width it takes the side pane's place. A player docked as a band keeps its band, so
    // there Queue shows in the content instead.
    val showWideQueueColumn = desktop && showQueue && wide && !ultraWide
    val queueReplacesSidePane = desktop && showQueue && !wide && dock.isSide
    val queueInContent = desktop && showQueue && !wide && !dock.isSide
    val playerPaneShown = desktop && !queueReplacesSidePane && nowPlayingPaneShown(nowPlayingPaneExpanded, nothingEverPlayed, remote.available, showNowPlaying)
    val showSideNowPlaying = playerPaneShown && dock.isSide
    val showBandNowPlaying = playerPaneShown && !dock.isSide
    val showDesktopMiniBar = desktop &&
        currentTrack != null &&
        !playerPaneShown &&
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

    /** The player's queue button: opens the overlay, or toggles the ultrawide column. */
    fun openQueue() {
        if (ultraWide) queueColumnChoice = !queueColumnOpen else showQueue = true
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
        if (dockDrag.cancel()) return true
        if (dismissOverlayStack()) return true
        if (destination == AppDestination.Search && searchPresenter.closePlaylist()) return true
        screenBack?.let { back ->
            back()
            return true
        }
        if (tabBackStackNames.isNotEmpty()) {
            destinationName = tabBackStackNames.last()
            tabBackStackNames = tabBackStackNames.dropLast(1)
            return true
        }
        return false
    }

    fun dockPlayer(edge: DockEdge) {
        searchScope.launch { container.updateSettings { it.copy(nowPlayingDock = edge.setting) } }
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

    /** Turns shuffle on first, so the fresh order starts from a random song of [tracks]. */
    fun shuffleTracks(tracks: List<Track>) {
        if (tracks.isEmpty()) return
        if (!container.player.queueState.value.shuffle) container.player.toggleShuffle()
        playTracks(tracks, Random.nextInt(tracks.size))
    }

    fun playSearchTracks(tracks: List<Track>, startIndex: Int = 0) {
        if (tracks.isEmpty()) return
        val index = startIndex.coerceIn(0, tracks.lastIndex)
        container.library.recordPlay(tracks[index])
        container.playSearchResults(tracks, startIndex = index)
        openNowPlaying()
    }

    /** Play or Shuffle on a Search playlist page; Shuffle starts on a random song of a fresh order. */
    fun playPlaylist(tracks: List<Track>, startIndex: Int, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        if (shuffle && !container.player.queueState.value.shuffle) container.player.toggleShuffle()
        playTracks(tracks, if (shuffle) Random.nextInt(tracks.size) else startIndex)
    }

    // Queue → Now Playing → Search playlist → previous tab → … → Home. At root Home, let the system leave the app.
    PlatformBackHandler(
        enabled = showQueue ||
            showNowPlaying ||
            (destination == AppDestination.Search && searchPlaylist != null) ||
            (desktop && nowPlayingPaneExpanded && compactPlayer) ||
            screenBack != null ||
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
            UiRequest.TOGGLE_QUEUE -> if (ultraWide) queueColumnChoice = !queueColumnOpen else showQueue = !showQueue
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

    DockDropHost(
        drag = dockDrag,
        current = dock,
        sidePaneWidth = nowPlayingPaneWidthDp.dp.coerceIn(DesktopNowPlayingPaneMinWidth, DesktopNowPlayingPaneMaxWidth),
        bandHeight = nowPlayingBandHeight,
        queueWidth = if (desktop && queueColumnOpen) DesktopQueueColumnWidth + (if (frost) 10.dp else 1.dp) else 0.dp,
        railInset = if (desktop) 88.dp + (if (frost) 10.dp else 1.dp) else 0.dp,
        frameInset = if (frost) 10.dp else 0.dp,
    ) {
    FrostOrPlainGround(frost) {
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
        // Frost floats each part as its own glass sheet over the wallpaper, like the shell's islands.
        val sheetGap = if (frost) 10.dp else 0.dp
        val hidePlayer = {
            nowPlayingPaneExpanded = false
            showNowPlaying = false
        }
        DockedFrame(
            dock = dock,
            frost = frost,
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).padding(sheetGap),
            rail = if (!desktop) null else {
                {
                    KainosRail(
                        selected = destination,
                        onSelect = ::navigateToTab,
                        footer = {
                            if (!playerPaneShown && nowPlayingPaneShown(true, nothingEverPlayed, remote.available, showNowPlaying)) {
                                IconButton(onClick = { openNowPlaying() }) {
                                    Icon(dock.opensToward(), contentDescription = "Show now playing")
                                }
                            }
                        },
                    )
                }
            },
            sidePane = when {
                queueReplacesSidePane -> { { QueueScreen(container) { showQueue = false } } }
                showSideNowPlaying -> {
                    {
                        Column(Modifier.fillMaxSize()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 4.dp, end = 4.dp, top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = hidePlayer) {
                                    Icon(dock.closesToward(), contentDescription = "Hide now playing")
                                }
                                DockGrip(dockDrag, dock, ::dockPlayer, Modifier.weight(1f)) {
                                    Text(
                                        "Now playing",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                NowPlayingScreen(
                                    container,
                                    onOpenQueue = ::openQueue,
                                    compact = true,
                                )
                            }
                        }
                    }
                }
                else -> null
            },
            sidePaneWidth = nowPlayingPaneWidthDp.dp.coerceIn(DesktopNowPlayingPaneMinWidth, DesktopNowPlayingPaneMaxWidth),
            onGrowSidePane = if (queueReplacesSidePane) null else { delta ->
                nowPlayingPaneWidthDp = (nowPlayingPaneWidthDp + delta.value)
                    .coerceIn(DesktopNowPlayingPaneMinWidth.value, DesktopNowPlayingPaneMaxWidth.value)
            },
            band = if (!showBandNowPlaying) null else {
                {
                    // The player spans the window, the cover beside its controls.
                    Box(Modifier.fillMaxSize()) {
                        NowPlayingScreen(
                            container,
                            onOpenQueue = ::openQueue,
                            onClose = hidePlayer,
                            compact = true,
                            band = true,
                            closeIcon = dock.closesToward(),
                        )
                        DockGrip(dockDrag, dock, ::dockPlayer, Modifier.align(Alignment.TopCenter))
                    }
                }
            },
            bandHeight = nowPlayingBandHeight,
            onGrowBand = { delta ->
                nowPlayingBandDraggedDp = clampNowPlayingBandHeight(nowPlayingBandHeight + delta, windowHeight).value
            },
            notices = {
                FailureBar(container.failures)
                HandoffNoticeBar(container)
            },
            queueColumn = if (!desktop || !queueColumnOpen) null else {
                { QueueScreen(container) { queueColumnChoice = false } }
            },
            footer = {
                if (showDesktopMiniBar) {
                    if (frost) Spacer(Modifier.height(sheetGap))
                    currentTrack?.let { track ->
                        DesktopMiniPlayer(
                            container = container,
                            track = track,
                            canSkipNext = canSkipNext,
                            onOpen = { openNowPlaying() },
                        )
                    }
                }
            },
        ) {
            // Main content: tabs stay composed across resize and dock moves so Library scroll survives.
            SheetOrPlain(frost, Modifier.weight(1f).fillMaxSize()) {
                when {
                    showQueue && (!desktop || queueInContent) -> QueueScreen(container) { showQueue = false }
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
                                // The docked pane already shows the session, so Home skips its session card.
                                playerPaneVisible = playerPaneShown,
                            )
                            AppDestination.Search -> SearchScreen(
                                presenter = searchPresenter,
                                onPlayTrackInList = ::playSearchTracks,
                                onPlayPlaylist = ::playPlaylist,
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
                                onShuffleTracks = ::shuffleTracks,
                                onInnerBack = { screenBack = it },
                            )
                            AppDestination.Settings -> SettingsScreen(container, settingsTarget)
                        }
                    }
                }
                // Overlays the list instead of pushing it, so rapid row actions never shift rows.
                TrackActionNoticeBar(trackActions, Modifier.align(Alignment.BottomCenter))
            }

            if (showWideQueueColumn) {
                PaneSeam(frost)
                SkinPane(
                    Modifier
                        .width(DesktopQueueBesideWidth)
                        .fillMaxHeight(),
                ) {
                    QueueScreen(container) { showQueue = false }
                }
            }
        }
    }
    }
    }
}

/** The arrow on the rail that opens the player points the way the pane slides in from its edge. */
private fun DockEdge.opensToward(): ImageVector = when (this) {
    DockEdge.LEFT -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    DockEdge.RIGHT -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
    DockEdge.TOP -> Icons.Default.KeyboardArrowDown
    DockEdge.BOTTOM -> Icons.Default.KeyboardArrowUp
}

/** The pane's own hide arrow points back at its edge. */
private fun DockEdge.closesToward(): ImageVector = when (this) {
    DockEdge.LEFT -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
    DockEdge.RIGHT -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    DockEdge.TOP -> Icons.Default.KeyboardArrowUp
    DockEdge.BOTTOM -> Icons.Default.KeyboardArrowDown
}

/** Frost draws the desktop wallpaper under the whole window; other skins keep their own ground. */
@Composable
private fun FrostOrPlainGround(frost: Boolean, content: @Composable () -> Unit) {
    if (frost) FrostGround(Modifier.fillMaxSize(), content) else content()
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
