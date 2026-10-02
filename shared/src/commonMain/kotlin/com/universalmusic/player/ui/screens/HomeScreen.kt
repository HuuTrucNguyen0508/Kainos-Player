package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.library.folderPinDisplayName
import com.universalmusic.player.data.library.requiresNetworkToPlay
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.defaultLocalMusicFolder
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.theme.providerColor
import kotlinx.coroutines.launch

private data class ResolvedHomePin(
    val pin: PersistedHomePin,
    val status: PinStatus,
    val detail: String,
)

private enum class PinStatus {
    READY,
    EMPTY,
    MISSING,
    LOADING,
    DISCONNECTED,
    RATE_LIMITED,
    ERROR,
}

private sealed class SessionFocus {
    data class Restored(val track: Track, val isPlaying: Boolean) : SessionFocus()
    data class LastPlayed(val track: Track, val queue: List<Track>) : SessionFocus()
}

@Composable
fun HomeScreen(
    container: AppContainer,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
    playerPaneVisible: Boolean = false,
) {
    val recent by container.library.recentlyPlayed.collectAsState()
    val homePins by container.library.homePins.collectAsState()
    val favorites by container.library.favoriteIds.collectAsState()
    val spotifyState by container.spotify.state.collectAsState()
    val youtubeState by container.youtube.state.collectAsState()
    val localState by container.local.state.collectAsState()
    val localTracks by container.local.libraryTracks.collectAsState()
    val discoverWeekly by container.spotifyDiscoverWeekly.collectAsState()
    val kainosPlaylists by container.kainosPlaylists.playlists.collectAsState()
    val spotifyPlaylists by container.spotifyPlaylists.collectAsState()
    val now by container.player.nowPlaying.collectAsState()
    val settings by container.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var discoverBusy by remember { mutableStateOf(false) }
    var discoverError by remember { mutableStateOf<String?>(null) }
    var pinBusyId by remember { mutableStateOf<String?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var editingPins by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    val sessionFocus: SessionFocus? = remember(now.track, now.isPlaying, recent) {
        val sessionTrack = now.track
        when {
            sessionTrack != null -> SessionFocus.Restored(sessionTrack, now.isPlaying)
            recent.isNotEmpty() -> SessionFocus.LastPlayed(recent.first(), recent)
            else -> null
        }
    }
    val continueListening = remember(recent, sessionFocus, playerPaneVisible) {
        val excludeId = when {
            playerPaneVisible -> null
            sessionFocus is SessionFocus.Restored -> sessionFocus.track.canonicalId
            sessionFocus is SessionFocus.LastPlayed -> sessionFocus.track.canonicalId
            else -> null
        }
        recent
            .asSequence()
            .filter { excludeId == null || it.canonicalId != excludeId }
            .take(8)
            .toList()
    }
    val loadedDiscover = discoverWeekly?.takeIf { playlist ->
        playlist.tracks.isNotEmpty() || (playlist.trackCount ?: 0) > 0
    }
    val empty = recent.isEmpty() &&
        localTracks.isEmpty() &&
        loadedDiscover == null &&
        homePins.isEmpty() &&
        favorites.isEmpty() &&
        now.track == null
    val favoriteCount = favorites.size
    val configuredFolders = remember(settings.localMusicFolders, settings.localMusicFoldersConfigured) {
        if (settings.localMusicFoldersConfigured) {
            settings.localMusicFolders
        } else {
            listOfNotNull(defaultLocalMusicFolder().takeIf { it.isNotBlank() })
        }
    }

    val folderTrackCounts = remember(configuredFolders, localTracks) {
        configuredFolders.associateWith { folder -> container.tracksInLocalFolder(folder).size }
    }
    val resolvedPins = remember(
        homePins,
        kainosPlaylists,
        spotifyPlaylists,
        localTracks,
        configuredFolders,
        folderTrackCounts,
        spotifyState,
        pinBusyId,
    ) {
        homePins.map { pin ->
            resolveHomePin(
                pin = pin,
                kainosTitles = kainosPlaylists.associate { it.id to it.title },
                kainosTrackCounts = kainosPlaylists.associate { it.id to it.entries.size },
                spotifyPlaylists = spotifyPlaylists,
                albumTrackCounts = localTracks
                    .mapNotNull { t -> t.album?.canonicalId?.let { id -> id to t } }
                    .groupBy({ it.first }, { it.second })
                    .mapValues { it.value.size },
                albumTitles = localTracks.mapNotNull { t -> t.album?.let { it.canonicalId to it.title } }.toMap(),
                folders = configuredFolders,
                folderTrackCounts = folderTrackCounts,
                spotifyState = spotifyState,
                busyPinId = pinBusyId,
            )
        }
    }

    fun playDiscover(playlist: Playlist) {
        scope.launch {
            discoverBusy = true
            discoverError = null
            try {
                val tracks = playlist.tracks.takeIf { it.isNotEmpty() }
                    ?: container.loadSpotifyPlaylistTracks(playlist.source.providerEntityId)
                if (tracks.isEmpty()) {
                    discoverError =
                        "Discover Weekly has no playable tracks. Paste your share link in Settings → Spotify, then refresh."
                } else {
                    onPlayTracks(tracks, 0)
                    onOpenNowPlaying()
                }
            } catch (failure: Exception) {
                discoverError = failure.message ?: "Could not load Discover Weekly."
            } finally {
                discoverBusy = false
            }
        }
    }

    fun playPin(resolved: ResolvedHomePin) {
        val pin = resolved.pin
        when (resolved.status) {
            PinStatus.DISCONNECTED -> {
                pinError = "Connect Spotify in Settings to play \"${pin.title}\"."
                return
            }
            PinStatus.RATE_LIMITED -> {
                pinError = "Spotify is rate-limited. Try again in a moment."
                return
            }
            PinStatus.MISSING -> {
                pinError = resolved.detail
                return
            }
            PinStatus.EMPTY -> {
                pinError = "\"${pin.title}\" has nothing to play yet."
                return
            }
            PinStatus.LOADING, PinStatus.ERROR -> return
            PinStatus.READY -> Unit
        }
        scope.launch {
            pinBusyId = pin.id
            pinError = null
            try {
                val played = when (pin.kind) {
                    HomePinKind.KAINOS_PLAYLIST -> container.playKainosPlaylist(pin.targetId)
                    HomePinKind.ALBUM -> container.playAlbumById(pin.targetId)
                    HomePinKind.LOCAL_FOLDER -> container.playLocalFolder(pin.targetId)
                    HomePinKind.PROVIDER_PLAYLIST -> {
                        val entityId = pin.providerEntityId
                            ?: pin.targetId.removePrefix("spotify-playlist:")
                        val tracks = container.loadSpotifyPlaylistTracks(entityId)
                        if (tracks.isEmpty()) {
                            pinError = "No playable tracks in \"${pin.title}\"."
                            false
                        } else {
                            onPlayTracks(tracks, 0)
                            true
                        }
                    }
                }
                if (played) {
                    onOpenNowPlaying()
                } else if (pinError == null) {
                    pinError = "Could not play \"${pin.title}\"."
                }
            } catch (failure: Exception) {
                pinError = failure.message ?: "Could not play \"${pin.title}\"."
            } finally {
                pinBusyId = null
            }
        }
    }

    fun playFavorites() {
        actionMessage = null
        if (container.playFavorites()) {
            onOpenNowPlaying()
        } else {
            actionMessage = if (favoriteCount == 0) {
                "Heart tracks in Library to build favorites."
            } else {
                "Favorites need a connection or local file to play."
            }
        }
    }

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
            if (!empty) {
                ProviderAttentionLine(
                    localState = localState,
                    spotifyState = spotifyState,
                    youtubeState = youtubeState,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp),
                )
            }

            if (!playerPaneVisible && sessionFocus != null) {
                Spacer(Modifier.height(12.dp))
                SessionCard(
                    focus = sessionFocus,
                    onOpenNowPlaying = onOpenNowPlaying,
                    onResume = {
                        actionMessage = null
                        container.resumeListening()
                        onOpenNowPlaying()
                    },
                    onPlayLast = { track, queue ->
                        actionMessage = null
                        val index = queue.indexOfFirst { it.canonicalId == track.canonicalId }.coerceAtLeast(0)
                        onPlayTracks(queue, index)
                        onOpenNowPlaying()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }

            Spacer(Modifier.height(if (playerPaneVisible) 16.dp else 12.dp))
            PlayFavoritesAction(
                favoriteCount = favoriteCount,
                onPlayFavorites = ::playFavorites,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            actionMessage?.let {
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
                        PinnedSection(
                            resolvedPins = resolvedPins,
                            homePinsEmpty = homePins.isEmpty(),
                            editingPins = editingPins,
                            onToggleEditing = { editingPins = !editingPins },
                            onPlayPin = ::playPin,
                            onMoveUp = { id -> container.library.moveHomePin(id, -1) },
                            onMoveDown = { id -> container.library.moveHomePin(id, 1) },
                            onRemove = { id ->
                                container.library.unpinHome(id)
                                if (homePins.size <= 1) editingPins = false
                            },
                        )
                        pinError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                        loadedDiscover?.let { playlist ->
                            Spacer(Modifier.height(20.dp))
                            DiscoverBlock(
                                playlist = playlist,
                                busy = discoverBusy,
                                onClick = { playDiscover(playlist) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                        discoverError?.let {
                            Text(
                                it,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
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
                PinnedSection(
                    resolvedPins = resolvedPins,
                    homePinsEmpty = homePins.isEmpty(),
                    editingPins = editingPins,
                    onToggleEditing = { editingPins = !editingPins },
                    onPlayPin = ::playPin,
                    onMoveUp = { id -> container.library.moveHomePin(id, -1) },
                    onMoveDown = { id -> container.library.moveHomePin(id, 1) },
                    onRemove = { id ->
                        container.library.unpinHome(id)
                        if (homePins.size <= 1) editingPins = false
                    },
                )
                pinError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                loadedDiscover?.let { playlist ->
                    Spacer(Modifier.height(20.dp))
                    DiscoverBlock(
                        playlist = playlist,
                        busy = discoverBusy,
                        onClick = { playDiscover(playlist) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                discoverError?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }

                ContinueListeningSection(
                    tracks = continueListening,
                    onPlayTracks = onPlayTracks,
                )
            }

            if (empty) {
                EmptyDoors(
                    localState = localState,
                    spotifyState = spotifyState,
                    youtubeState = youtubeState,
                    onOpenSettings = onOpenSettings,
                    onOpenSearch = onOpenSearch,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 40.dp),
                )
            }
        }
    }
}

@Composable
private fun PinnedSection(
    resolvedPins: List<ResolvedHomePin>,
    homePinsEmpty: Boolean,
    editingPins: Boolean,
    onToggleEditing: () -> Unit,
    onPlayPin: (ResolvedHomePin) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Pinned",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!homePinsEmpty) {
            TextButton(onClick = onToggleEditing) {
                Text(if (editingPins) "Done" else "Edit")
            }
        }
    }
    when {
        resolvedPins.isEmpty() -> {
            Text(
                "Pin playlists, albums or folders from Library",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        else -> {
            resolvedPins.forEachIndexed { index, resolved ->
                PinRow(
                    resolved = resolved,
                    editing = editingPins,
                    canMoveUp = index > 0,
                    canMoveDown = index < resolvedPins.lastIndex,
                    onPlay = { onPlayPin(resolved) },
                    onMoveUp = { onMoveUp(resolved.pin.id) },
                    onMoveDown = { onMoveDown(resolved.pin.id) },
                    onRemove = { onRemove(resolved.pin.id) },
                )
            }
        }
    }
}

@Composable
private fun ContinueListeningSection(
    tracks: List<Track>,
    onPlayTracks: (List<Track>, Int) -> Unit,
) {
    if (tracks.isEmpty()) return
    Text(
        "Continue listening",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
    )
    tracks.forEachIndexed { index, track ->
        LedgerRow(
            track = track,
            onClick = { onPlayTracks(tracks, index) },
        )
    }
}

private fun resolveHomePin(
    pin: PersistedHomePin,
    kainosTitles: Map<String, String>,
    kainosTrackCounts: Map<String, Int>,
    spotifyPlaylists: List<Playlist>,
    albumTrackCounts: Map<String, Int>,
    albumTitles: Map<String, String>,
    folders: List<String>,
    folderTrackCounts: Map<String, Int>,
    spotifyState: ProviderState,
    busyPinId: String?,
): ResolvedHomePin {
    if (pin.id == busyPinId) {
        return ResolvedHomePin(pin, PinStatus.LOADING, "Loading…")
    }
    return when (pin.kind) {
        HomePinKind.KAINOS_PLAYLIST -> {
            val count = kainosTrackCounts[pin.targetId]
            when {
                count == null -> ResolvedHomePin(
                    pin,
                    PinStatus.MISSING,
                    "Playlist removed from this device (unpin to clear).",
                )
                count == 0 -> ResolvedHomePin(pin, PinStatus.EMPTY, "Kainos · empty")
                else -> ResolvedHomePin(
                    pin.copy(title = kainosTitles[pin.targetId] ?: pin.title),
                    PinStatus.READY,
                    "Kainos · $count tracks",
                )
            }
        }
        HomePinKind.PROVIDER_PLAYLIST -> {
            when (spotifyState) {
                ProviderState.RATE_LIMITED ->
                    ResolvedHomePin(pin, PinStatus.RATE_LIMITED, "Spotify · Limited")
                ProviderState.AVAILABLE -> {
                    val live = spotifyPlaylists.firstOrNull { it.canonicalId == pin.targetId }
                    val detail = live?.trackCount?.let { "Spotify · $it tracks" }
                        ?: "Spotify playlist"
                    ResolvedHomePin(
                        pin.copy(title = live?.title ?: pin.title),
                        PinStatus.READY,
                        detail,
                    )
                }
                ProviderState.LOADING ->
                    ResolvedHomePin(pin, PinStatus.LOADING, "Connecting to Spotify…")
                else ->
                    ResolvedHomePin(pin, PinStatus.DISCONNECTED, "Spotify · Needs connection")
            }
        }
        HomePinKind.ALBUM -> {
            val count = albumTrackCounts[pin.targetId]
            when {
                count == null -> ResolvedHomePin(
                    pin,
                    PinStatus.MISSING,
                    "Album not in local library.",
                )
                count == 0 -> ResolvedHomePin(pin, PinStatus.EMPTY, "Album · empty")
                else -> ResolvedHomePin(
                    pin.copy(title = albumTitles[pin.targetId] ?: pin.title),
                    PinStatus.READY,
                    "Album · $count tracks",
                )
            }
        }
        HomePinKind.LOCAL_FOLDER -> {
            if (folders.none { it == pin.targetId }) {
                ResolvedHomePin(
                    pin,
                    PinStatus.MISSING,
                    "Folder not in Settings music folders.",
                )
            } else {
                val count = folderTrackCounts[pin.targetId] ?: 0
                val title = pin.title.ifBlank { folderPinDisplayName(pin.targetId) }
                if (count == 0) {
                    ResolvedHomePin(pin.copy(title = title), PinStatus.EMPTY, "Folder · no tracks yet")
                } else {
                    ResolvedHomePin(pin.copy(title = title), PinStatus.READY, "Folder · $count tracks")
                }
            }
        }
    }
}

@Composable
private fun PlayFavoritesAction(
    favoriteCount: Int,
    onPlayFavorites: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = onPlayFavorites,
        enabled = favoriteCount > 0,
        modifier = modifier,
    ) {
        Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (favoriteCount > 0) "Play favorites" else "No favorites yet",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionCard(
    focus: SessionFocus,
    onOpenNowPlaying: () -> Unit,
    onResume: () -> Unit,
    onPlayLast: (Track, List<Track>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = when (focus) {
        is SessionFocus.Restored -> focus.track
        is SessionFocus.LastPlayed -> focus.track
    }
    val playable = track.playableSources().firstOrNull()
    val sourceLabel = playable?.provider?.let { shortProviderName(it) } ?: "Needs connection"
    val eyebrow = when (focus) {
        is SessionFocus.Restored -> if (focus.isPlaying) "Playing" else "Resume"
        is SessionFocus.LastPlayed -> "Last played"
    }
    val onCardClick: () -> Unit = when (focus) {
        is SessionFocus.Restored -> onOpenNowPlaying
        is SessionFocus.LastPlayed -> {
            { onPlayLast(focus.track, focus.queue) }
        }
    }
    val onPlayClick: () -> Unit = when (focus) {
        is SessionFocus.Restored -> {
            {
                if (focus.isPlaying) {
                    onOpenNowPlaying()
                } else {
                    onResume()
                }
            }
        }
        is SessionFocus.LastPlayed -> {
            { onPlayLast(focus.track, focus.queue) }
        }
    }

    BoxWithConstraints(modifier) {
        val artSize = if (maxWidth >= 600.dp) 88.dp else 72.dp
        Surface(
            onClick = onCardClick,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    track.artwork,
                    track.title,
                    Modifier.size(artSize),
                    track.title,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artistLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "$eyebrow · $sourceLabel",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.width(10.dp))
                FilledIconButton(onClick = onPlayClick, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = when (focus) {
                            is SessionFocus.Restored -> if (focus.isPlaying) "Open now playing" else "Resume"
                            is SessionFocus.LastPlayed -> "Play"
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PinRow(
    resolved: ResolvedHomePin,
    editing: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val pin = resolved.pin
    val enabled = resolved.status == PinStatus.READY && !editing
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onPlay)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            when (resolved.status) {
                PinStatus.LOADING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = when (resolved.status) {
                        PinStatus.READY -> MaterialTheme.colorScheme.primary
                        PinStatus.DISCONNECTED, PinStatus.RATE_LIMITED, PinStatus.ERROR, PinStatus.MISSING ->
                            MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    pin.title.ifBlank { pin.targetId },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                resolved.detail,
                style = MaterialTheme.typography.bodySmall,
                color = when (resolved.status) {
                    PinStatus.READY, PinStatus.EMPTY, PinStatus.LOADING ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (editing) {
            IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
            }
            IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Default.Close, contentDescription = "Unpin ${pin.title}")
            }
        } else if (resolved.status == PinStatus.READY) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = "Play ${pin.title}",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ProviderAttentionLine(
    localState: ProviderState,
    spotifyState: ProviderState,
    youtubeState: ProviderState,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val attention = remember(localState, spotifyState, youtubeState) {
        listOf(
            ProviderId.SPOTIFY to spotifyState,
            ProviderId.YOUTUBE_MUSIC to youtubeState,
            ProviderId.LOCAL to localState,
        ).firstOrNull { (_, state) ->
            state != ProviderState.AVAILABLE && state != ProviderState.LOADING
        }
    } ?: return

    val (provider, state) = attention
    val shortName = when (provider) {
        ProviderId.LOCAL -> "Local library"
        ProviderId.SPOTIFY -> "Spotify"
        ProviderId.YOUTUBE_MUSIC -> "YouTube"
        ProviderId.SAMPLE -> provider.displayName
    }
    val message = when (state) {
        ProviderState.AUTH_REQUIRED -> "$shortName needs sign-in · Open Settings"
        ProviderState.NOT_CONFIGURED -> "$shortName not set up · Open Settings"
        ProviderState.UNAVAILABLE -> "$shortName is offline · Open Settings"
        ProviderState.RATE_LIMITED -> "$shortName is limited · Try again soon"
        else -> {
            val word = legendStateWord(state) ?: return
            "$shortName · $word · Open Settings"
        }
    }

    Text(
        message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.then(
            if (onOpenSettings != null) {
                Modifier
                    .clickable(onClick = onOpenSettings)
                    .semantics { contentDescription = message }
            } else {
                Modifier
            },
        ),
    )
}

@Composable
private fun DiscoverBlock(
    playlist: Playlist,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkImage(
            playlist.artwork,
            playlist.title,
            Modifier.size(72.dp),
            playlist.title,
        )
        Column(Modifier.weight(1f)) {
            Text(
                "New this week",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                playlist.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when {
                    busy -> "Loading tracks…"
                    else -> listOfNotNull(
                        playlist.trackCount?.let { "$it tracks" },
                        "Spotify",
                    ).joinToString(" · ")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun LedgerRow(
    track: Track,
    onClick: () -> Unit,
) {
    val playable = track.playableSources().firstOrNull()
    val railColor = playable?.provider?.let { providerColor(it.displayName) }
        ?: MaterialTheme.colorScheme.outlineVariant
    val sourceLabel = playable?.provider?.let { "Played from ${it.displayName}" }
        ?: "Not playable right now"
    val needsConnection = track.requiresNetworkToPlay() && playable == null
    val artistLine = buildString {
        append(track.artistLine)
        if (needsConnection) append(" · Needs connection")
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = 20.dp)
            .semantics { contentDescription = "${track.title}. $artistLine. $sourceLabel" },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(railColor),
        )
        Spacer(Modifier.width(14.dp))
        Surface(
            onClick = onClick,
            color = Color.Transparent,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.weight(1f),
        ) {
            Row(
                Modifier.padding(top = 6.dp, bottom = 6.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    track.artwork,
                    track.title,
                    Modifier.size(44.dp),
                    track.title,
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        track.title,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        artistLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                track.durationMs?.takeIf { it > 0 }?.let { ms ->
                    Text(
                        formatTime(ms),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyDoors(
    localState: ProviderState,
    spotifyState: ProviderState,
    youtubeState: ProviderState,
    onOpenSettings: (() -> Unit)?,
    onOpenSearch: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text("Nothing to play yet", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Text(
            "Kainos plays from three places. Open one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        EmptyDoor(
            provider = ProviderId.LOCAL,
            state = localState,
            name = "Local library",
            action = "Add a music folder in Settings",
            onClick = onOpenSettings,
        )
        EmptyDoor(
            provider = ProviderId.SPOTIFY,
            state = spotifyState,
            name = "Spotify",
            action = "Connect in Settings",
            onClick = onOpenSettings,
        )
        EmptyDoor(
            provider = ProviderId.YOUTUBE_MUSIC,
            state = youtubeState,
            name = "YouTube Music",
            action = "Search for anything and play it",
            onClick = onOpenSearch,
        )
    }
}

@Composable
private fun EmptyDoor(
    provider: ProviderId,
    state: ProviderState,
    name: String,
    action: String,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(provider, state)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(name, style = MaterialTheme.typography.titleSmall)
            Text(
                action,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LegendDot(provider: ProviderId, state: ProviderState) {
    when (state) {
        ProviderState.AVAILABLE -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(providerColor(provider.displayName)),
        )
        ProviderState.LOADING -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
        )
        ProviderState.RATE_LIMITED -> Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error),
        )
        else -> Box(
            Modifier
                .size(8.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape),
        )
    }
}

private fun legendStateWord(state: ProviderState): String? = when (state) {
    ProviderState.LOADING -> "Connecting"
    ProviderState.AUTH_REQUIRED -> "Sign in"
    ProviderState.NOT_CONFIGURED -> "Not set up"
    ProviderState.UNAVAILABLE -> "Offline"
    ProviderState.RATE_LIMITED -> "Limited"
    ProviderState.AVAILABLE -> null
}

private fun shortProviderName(provider: ProviderId): String = when (provider) {
    ProviderId.LOCAL -> "Local"
    ProviderId.SPOTIFY -> "Spotify"
    ProviderId.YOUTUBE_MUSIC -> "YouTube"
    ProviderId.SAMPLE -> provider.displayName
}
