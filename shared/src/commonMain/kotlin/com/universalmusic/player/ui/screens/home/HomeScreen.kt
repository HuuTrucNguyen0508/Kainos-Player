package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.DisplayHero
import com.universalmusic.player.ui.components.Slip
import com.universalmusic.player.ui.theme.Accents

@Composable
internal fun HomeScreen(
    container: AppContainer,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: (SettingsTarget) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    trackActions: TrackActionsPresenter,
    playerPaneVisible: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val presenter = remember(container) { HomePresenter.from(container, scope) }
    LaunchedEffect(container) { container.recheckSourceTools() }
    HomeContent(presenter, onPlayTracks, onOpenNowPlaying, onOpenSettings, onOpenSearch, onOpenLibrary, playerPaneVisible, trackActions)
}

@Composable
internal fun HomeContent(
    presenter: HomePresenter,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: (SettingsTarget) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenLibrary: () -> Unit,
    playerPaneVisible: Boolean = false,
    trackActions: TrackActionsPresenter? = null,
) {
    val ui by presenter.state.collectAsState()
    var editingPins by remember { mutableStateOf(false) }
    val sessionFocus = ui.sessionFocus
    val showSessionCard = homeSessionCardVisible(playerPaneVisible, sessionFocus)
    val openDoor: (LibraryLine) -> Unit = { line ->
        when (line.door) {
            LibraryDoor.LIBRARY -> onOpenLibrary()
            LibraryDoor.FAVORITES -> presenter.playFavorites(onOpenNowPlaying = onOpenNowPlaying)
            LibraryDoor.SETTINGS -> line.settingsTarget?.let(onOpenSettings)
            LibraryDoor.SEARCH -> onOpenSearch()
            LibraryDoor.RETRY_LOCAL -> presenter.retryLocalLibrary()
            LibraryDoor.PICK_FOLDER -> presenter.pickMusicFolder()
        }
    }
    val act: (SourceAction) -> Unit = { action ->
        when (action) {
            SourceAction.RetryLocal -> presenter.retryLocalLibrary()
            SourceAction.PickMusicFolder -> presenter.pickMusicFolder()
            SourceAction.OpenLibrary -> onOpenLibrary()
            SourceAction.OpenSearch -> onOpenSearch()
            is SourceAction.OpenSettings -> onOpenSettings(action.target)
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val firstRunTop = (maxHeight * .1f).coerceIn(28.dp, 96.dp)
        val hasHistory = ui.shelves.isNotEmpty() ||
            continueListeningTracks(ui.recent, ui.sessionFocus, playerPaneVisible).isNotEmpty()
        val layout = homeColumns(maxWidth, hasHistory)
        // Each grid is sized against the column it sits in, not the page.
        val columns = continueListeningColumns((layout.end ?: layout.start).value.toInt())
        val continueListening = remember(ui.recent, ui.sessionFocus, playerPaneVisible, columns) {
            continueListeningTracks(ui.recent, ui.sessionFocus, playerPaneVisible, limit = continueListeningLimit(columns))
        }
        // The hero shares its row with the library card (and Discover when present).
        val heroShare = if (layout.cardsInRow) 1.25f / (if (ui.loadedDiscover != null) 3.25f else 2.25f) else 1f
        val heroStacked = layout.start * heroShare < 440.dp

        // "Start something": the cards that begin listening, and the pins.
        val startSection: @Composable ColumnScope.() -> Unit = {
            // The Echo plays apart from this app, so its card shows even beside the Now Playing pane.
            EchoOnHome(presenter.echo)

            if (showSessionCard && sessionFocus != null) {
                Spacer(Modifier.height(16.dp))
                SessionCard(
                    focus = sessionFocus,
                    onOpenNowPlaying = onOpenNowPlaying,
                    onResume = { presenter.resumeListening(onOpenNowPlaying) },
                    onPlayLast = { track, queue ->
                        presenter.clearActionMessage()
                        onPlayTracks(queue, lastPlayedStartIndex(queue, track))
                        onOpenNowPlaying()
                    },
                )
            }

            ui.firstSound?.let { local ->
                Spacer(Modifier.height(16.dp))
                FirstSoundCard(local, onPlay = { presenter.playLocalLibrary(onPlayTracks) }, onAct = act)
            }

            Spacer(Modifier.height(16.dp))
            val discover: @Composable (Modifier) -> Unit = { m ->
                ui.loadedDiscover?.let { playlist ->
                    Slip(m) {
                        DiscoverBlock(
                            playlist = playlist,
                            busy = ui.discoverBusy,
                            onClick = { presenter.playDiscover(playlist, onPlayTracks, onOpenNowPlaying) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            val favorites: @Composable (Modifier) -> Unit = { m ->
                FavoritesHero(
                    favorites = ui.favorites,
                    stacked = heroStacked,
                    onPlay = { presenter.playFavorites(onOpenNowPlaying = onOpenNowPlaying) },
                    onShuffle = { presenter.playFavorites(shuffle = true, onOpenNowPlaying = onOpenNowPlaying) },
                    modifier = m,
                )
            }
            val library: @Composable (Modifier) -> Unit = { m -> LibraryCard(ui.libraryLines, openDoor, m) }
            if (layout.cardsInRow) {
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (ui.loadedDiscover != null) discover(Modifier.weight(1f).fillMaxHeight())
                    favorites(Modifier.weight(1.25f).fillMaxHeight())
                    library(Modifier.weight(1f).fillMaxHeight())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    discover(Modifier.fillMaxWidth())
                    favorites(Modifier.fillMaxWidth())
                    library(Modifier.fillMaxWidth())
                }
            }
            listOfNotNull(ui.actionMessage, ui.discoverError).forEach { message ->
                Text(
                    message,
                    color = if (message == ui.discoverError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
            PinnedSection(
                resolvedPins = ui.resolvedPins,
                editingPins = editingPins,
                onToggleEditing = { editingPins = !editingPins },
                onPlayPin = { presenter.playPin(it, onPlayTracks, onOpenNowPlaying) },
                onMoveUp = { presenter.movePin(it, -1) },
                onMoveDown = { presenter.movePin(it, 1) },
                onRemove = { id ->
                    presenter.unpin(id)
                    if (ui.homePins.size <= 1) editingPins = false
                },
            )
            if (ui.resolvedPins.isNotEmpty() && ui.pinSuggestions.isNotEmpty()) Spacer(Modifier.height(12.dp))
            PinSuggestionsSection(
                suggestions = ui.pinSuggestions,
                onPlay = { presenter.playPin(it.asResolvedPin(), onPlayTracks, onOpenNowPlaying) },
                onPin = presenter::pinSuggestion,
            )
            ui.pinError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }
        }
        // "Pick up where you left off": what you played, and the history shelves.
        val historySection: @Composable ColumnScope.() -> Unit = {
            ContinueListeningSection(
                tracks = continueListening,
                columns = columns,
                onPlayTracks = onPlayTracks,
                trackActions = trackActions,
            )
            ui.shelves.forEachIndexed { index, shelf ->
                if (index > 0 || continueListening.isNotEmpty()) Spacer(Modifier.height(20.dp))
                HistoryShelf(shelf, columns, onPlayTracks, trackActions = trackActions)
            }
        }

        Column(
            Modifier
                .widthIn(max = layout.maxWidth)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(start = layout.sidePad, end = layout.sidePad, bottom = 24.dp),
        ) {
            // Until saved data loads, an empty Home could still be a returning listener's.
            if (ui.empty) {
                if (ui.ready) FirstRun(ui.sources, act, Modifier.padding(top = firstRunTop))
                return@Column
            }

            DisplayHero(
                title = "Home",
                eyebrow = homeEyebrow(ui.favorites.count, ui.localTrackCount),
                ghost = ui.favorites.count.takeIf { it > 0 }?.toString(),
                accent = Accents.home,
                modifier = Modifier.padding(top = 20.dp),
            )

            if (layout.end == null) {
                startSection()
                Spacer(Modifier.height(20.dp))
                historySection()
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(HomeSpreadGutter)) {
                    Column(Modifier.weight(HomeStartWeight)) { startSection() }
                    // Its first heading lines up with the top of the first card on the left.
                    Column(Modifier.weight(1f).padding(top = 16.dp)) { historySection() }
                }
            }
        }
    }
}
