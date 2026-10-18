package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.DisplayHero
import com.universalmusic.player.ui.components.Slip
import com.universalmusic.player.ui.theme.Accents

@Composable
fun HomeScreen(
    container: AppContainer,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    onOpenLibrary: (() -> Unit)? = null,
    playerPaneVisible: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val presenter = remember(container) { HomePresenter.from(container, scope) }
    val ui by presenter.state.collectAsState()
    var editingPins by remember { mutableStateOf(false) }
    val sessionFocus = ui.sessionFocus
    val showSessionCard = homeSessionCardVisible(playerPaneVisible, sessionFocus)
    val openDoor: (LibraryDoor) -> Unit = { door ->
        when (door) {
            LibraryDoor.LIBRARY -> onOpenLibrary?.invoke()
            LibraryDoor.FAVORITES -> presenter.playFavorites(onOpenNowPlaying = onOpenNowPlaying)
            LibraryDoor.SETTINGS -> onOpenSettings?.invoke()
            LibraryDoor.SEARCH -> onOpenSearch?.invoke()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 900.dp
        val sidePad = if (wide) 28.dp else 20.dp
        val columns = continueListeningColumns((maxWidth - sidePad * 2).value.toInt())
        val continueListening = remember(ui.recent, ui.sessionFocus, playerPaneVisible, columns) {
            continueListeningTracks(ui.recent, ui.sessionFocus, playerPaneVisible, limit = columns * CONTINUE_LISTENING_ROWS)
        }
        // The hero shares its row with the library card (and Discover when present).
        val heroShare = if (wide) 1.25f / (if (ui.loadedDiscover != null) 3.25f else 2.25f) else 1f
        val heroStacked = (maxWidth - sidePad * 2) * heroShare < 440.dp

        Column(
            Modifier
                .widthIn(max = 1480.dp)
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .verticalScroll(rememberScrollState())
                .padding(start = sidePad, end = sidePad, bottom = 24.dp),
        ) {
            DisplayHero(
                title = "Home",
                eyebrow = homeEyebrow(ui.favorites.count, ui.localTrackCount),
                ghost = ui.favorites.count.takeIf { it > 0 }?.toString(),
                accent = Accents.home,
                modifier = Modifier.padding(top = 20.dp),
            )

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

            if (ui.empty) {
                EmptyDoors(
                    localState = ui.localState,
                    spotifyState = ui.spotifyState,
                    youtubeState = ui.youtubeState,
                    onOpenSettings = onOpenSettings,
                    onOpenSearch = onOpenSearch,
                    modifier = Modifier.padding(vertical = 40.dp),
                )
                return@Column
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
            if (wide) {
                // Intrinsic height keeps the cards level; none of them may use BoxWithConstraints.
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

            Spacer(Modifier.height(20.dp))
            ContinueListeningSection(
                tracks = continueListening,
                columns = columns,
                onPlayTracks = onPlayTracks,
            )
        }
    }
}
