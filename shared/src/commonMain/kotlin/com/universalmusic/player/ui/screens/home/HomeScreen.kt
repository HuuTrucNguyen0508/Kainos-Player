package com.universalmusic.player.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.Track

@Composable
fun HomeScreen(
    container: AppContainer,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    playerPaneVisible: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val presenter = remember(container) { HomePresenter.from(container, scope) }
    val ui by presenter.state.collectAsState()
    var editingPins by remember { mutableStateOf(false) }
    val continueListening = remember(ui.recent, ui.sessionFocus, playerPaneVisible) {
        continueListeningTracks(ui.recent, ui.sessionFocus, playerPaneVisible)
    }
    val sessionFocus = ui.sessionFocus
    val showSessionCard = homeSessionCardVisible(playerPaneVisible, sessionFocus)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val useTwoColumn = playerPaneVisible && maxWidth >= 780.dp
        val contentMaxWidth = when {
            useTwoColumn -> 1080.dp
            playerPaneVisible -> 640.dp
            maxWidth >= 900.dp -> 640.dp
            else -> 720.dp
        }

        Column(
            Modifier
                .widthIn(max = contentMaxWidth)
                .fillMaxWidth()
                .align(if (playerPaneVisible || maxWidth >= 900.dp) Alignment.TopCenter else Alignment.TopStart)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            if (!ui.empty) {
                ProviderAttentionLine(
                    message = ui.attentionMessage,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                )
            }

            if (showSessionCard && sessionFocus != null) {
                Spacer(Modifier.height(12.dp))
                SessionCard(
                    focus = sessionFocus,
                    onOpenNowPlaying = onOpenNowPlaying,
                    onResume = { presenter.resumeListening(onOpenNowPlaying) },
                    onPlayLast = { track, queue ->
                        presenter.clearActionMessage()
                        onPlayTracks(queue, lastPlayedStartIndex(queue, track))
                        onOpenNowPlaying()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Spacer(Modifier.height(if (playerPaneVisible) 16.dp else 12.dp))
            PlayFavoritesAction(
                favoriteCount = ui.favoriteCount,
                onPlayFavorites = { presenter.playFavorites(onOpenNowPlaying) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            ui.actionMessage?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            if (useTwoColumn) {
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        HomePinsAndDiscover(
                            ui = ui,
                            editingPins = editingPins,
                            onToggleEditing = { editingPins = !editingPins },
                            onPlayPin = { presenter.playPin(it, onPlayTracks, onOpenNowPlaying) },
                            onMoveUp = { presenter.movePin(it, -1) },
                            onMoveDown = { presenter.movePin(it, 1) },
                            onRemove = { id ->
                                presenter.unpin(id)
                                if (ui.homePins.size <= 1) editingPins = false
                            },
                            onPlayDiscover = { presenter.playDiscover(it, onPlayTracks, onOpenNowPlaying) },
                            pinErrorPadding = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            discoverPadding = Modifier.padding(horizontal = 8.dp),
                            discoverErrorPadding = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        ContinueListeningSection(
                            tracks = continueListening,
                            onPlayTracks = onPlayTracks,
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(8.dp))
                HomePinsAndDiscover(
                    ui = ui,
                    editingPins = editingPins,
                    onToggleEditing = { editingPins = !editingPins },
                    onPlayPin = { presenter.playPin(it, onPlayTracks, onOpenNowPlaying) },
                    onMoveUp = { presenter.movePin(it, -1) },
                    onMoveDown = { presenter.movePin(it, 1) },
                    onRemove = { id ->
                        presenter.unpin(id)
                        if (ui.homePins.size <= 1) editingPins = false
                    },
                    onPlayDiscover = { presenter.playDiscover(it, onPlayTracks, onOpenNowPlaying) },
                    pinErrorPadding = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    discoverPadding = Modifier.padding(horizontal = 16.dp),
                    discoverErrorPadding = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                ContinueListeningSection(
                    tracks = continueListening,
                    onPlayTracks = onPlayTracks,
                )
            }

            if (ui.empty) {
                EmptyDoors(
                    localState = ui.localState,
                    spotifyState = ui.spotifyState,
                    youtubeState = ui.youtubeState,
                    onOpenSettings = onOpenSettings,
                    onOpenSearch = onOpenSearch,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 40.dp),
                )
            }
        }
    }
}

@Composable
private fun HomePinsAndDiscover(
    ui: HomeUiState,
    editingPins: Boolean,
    onToggleEditing: () -> Unit,
    onPlayPin: (ResolvedHomePin) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onRemove: (String) -> Unit,
    onPlayDiscover: (Playlist) -> Unit,
    pinErrorPadding: Modifier,
    discoverPadding: Modifier,
    discoverErrorPadding: Modifier,
) {
    PinnedSection(
        resolvedPins = ui.resolvedPins,
        homePinsEmpty = ui.homePins.isEmpty(),
        editingPins = editingPins,
        onToggleEditing = onToggleEditing,
        onPlayPin = onPlayPin,
        onMoveUp = onMoveUp,
        onMoveDown = onMoveDown,
        onRemove = onRemove,
    )
    ui.pinError?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = pinErrorPadding,
        )
    }
    ui.loadedDiscover?.let { playlist ->
        Spacer(Modifier.height(20.dp))
        DiscoverBlock(
            playlist = playlist,
            busy = ui.discoverBusy,
            onClick = { onPlayDiscover(playlist) },
            modifier = discoverPadding,
        )
    }
    ui.discoverError?.let {
        Text(
            it,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall,
            modifier = discoverErrorPadding,
        )
    }
}
