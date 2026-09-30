package com.universalmusic.player.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

private sealed class HomeHero {
    data class TrackHero(val track: Track, val queue: List<Track>, val playingNow: Boolean) : HomeHero()
    data class Discover(val playlist: Playlist) : HomeHero()
    data class Library(val tracks: List<Track>) : HomeHero()
}

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    container: AppContainer,
    onPlayTracks: (List<Track>, Int) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    onOpenSearch: (() -> Unit)? = null,
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
    val queue by container.player.queue.queue.collectAsState()
    val settings by container.settings.collectAsState()
    val spotifyLoading by container.spotifyLibraryLoading.collectAsState()
    val scope = rememberCoroutineScope()
    var discoverBusy by remember { mutableStateOf(false) }
    var discoverError by remember { mutableStateOf<String?>(null) }
    var pinBusyId by remember { mutableStateOf<String?>(null) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var editingPins by remember { mutableStateOf(false) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    val hero: HomeHero? = remember(recent, localTracks, discoverWeekly, now.track?.canonicalId) {
        when {
            recent.isNotEmpty() -> {
                val track = recent.first()
                HomeHero.TrackHero(
                    track = track,
                    queue = recent,
                    playingNow = now.track?.canonicalId == track.canonicalId,
                )
            }
            discoverWeekly != null -> HomeHero.Discover(discoverWeekly!!)
            localTracks.isNotEmpty() -> HomeHero.Library(localTracks)
            else -> null
        }
    }
    val continueListening = remember(recent, hero) {
        when (hero) {
            is HomeHero.TrackHero -> recent.drop(1).take(8)
            else -> recent.take(8)
        }
    }
    val empty = recent.isEmpty() &&
        localTracks.isEmpty() &&
        discoverWeekly == null &&
        homePins.isEmpty() &&
        favorites.isEmpty() &&
        now.track == null
    val spotifyConnected = spotifyState == ProviderState.AVAILABLE || spotifyState == ProviderState.RATE_LIMITED
    val showDiscoverSlot = discoverWeekly != null ||
        (spotifyConnected && hero !is HomeHero.Discover)
    val favoriteCount = favorites.size
    val canResume = now.track != null || queue.items.isNotEmpty()
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

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            if (!empty) {
                ProviderLegend(
                    localState = localState,
                    spotifyState = spotifyState,
                    youtubeState = youtubeState,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
                )
                Spacer(Modifier.height(8.dp))
            }

            HomeQuickActions(
                favoriteCount = favoriteCount,
                canResume = canResume,
                resumeLabel = when {
                    now.isPlaying -> "Playing"
                    else -> "Resume"
                },
                onPlayFavorites = {
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
                },
                onResume = {
                    actionMessage = null
                    if (!canResume) {
                        actionMessage = "Nothing to resume yet. Play something first."
                        return@HomeQuickActions
                    }
                    container.resumeListening()
                    onOpenNowPlaying()
                },
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

            when (val current = hero) {
                is HomeHero.TrackHero -> {
                    Spacer(Modifier.height(16.dp))
                    HeroTrackBlock(
                        hero = current,
                        onPlay = {
                            onPlayTracks(current.queue, 0)
                            onOpenNowPlaying()
                        },
                        onOpenNowPlaying = onOpenNowPlaying,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                is HomeHero.Discover -> {
                    Spacer(Modifier.height(16.dp))
                    DiscoverBlock(
                        playlist = current.playlist,
                        hero = true,
                        busy = discoverBusy,
                        onClick = { playDiscover(current.playlist) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                is HomeHero.Library -> {
                    Spacer(Modifier.height(16.dp))
                    HeroLibraryBlock(
                        trackCount = current.tracks.size,
                        onPlay = {
                            onPlayTracks(current.tracks, 0)
                            onOpenNowPlaying()
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                null -> Unit
            }

            if (showDiscoverSlot && hero !is HomeHero.Discover) {
                Spacer(Modifier.height(20.dp))
                if (discoverWeekly != null) {
                    DiscoverBlock(
                        playlist = discoverWeekly!!,
                        hero = false,
                        busy = discoverBusy,
                        onClick = { playDiscover(discoverWeekly!!) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                } else {
                    DiscoverWeeklyPlaceholder(
                        loading = spotifyLoading,
                        onRefresh = {
                            scope.launch { container.refreshSpotifyLibrary() }
                        },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }

            discoverError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            Spacer(Modifier.height(20.dp))
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
                if (homePins.isNotEmpty()) {
                    TextButton(onClick = { editingPins = !editingPins }) {
                        Text(if (editingPins) "Done" else "Edit")
                    }
                }
            }
            when {
                resolvedPins.isEmpty() -> {
                    Text(
                        "Pin playlists, albums, or folders from Library or Settings. Recents stay under Continue listening.",
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
                            onPlay = { playPin(resolved) },
                            onMoveUp = { container.library.moveHomePin(resolved.pin.id, -1) },
                            onMoveDown = { container.library.moveHomePin(resolved.pin.id, 1) },
                            onRemove = {
                                container.library.unpinHome(resolved.pin.id)
                                if (homePins.size <= 1) editingPins = false
                            },
                        )
                    }
                }
            }
            pinError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }

            if (continueListening.isNotEmpty()) {
                Text(
                    "Continue listening",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
                )
                continueListening.forEachIndexed { index, track ->
                    LedgerRow(
                        track = track,
                        onClick = { onPlayTracks(continueListening, index) },
                    )
                }
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
private fun HomeQuickActions(
    favoriteCount: Int,
    canResume: Boolean,
    resumeLabel: String,
    onPlayFavorites: () -> Unit,
    onResume: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = onPlayFavorites,
            modifier = Modifier.weight(1f),
            enabled = favoriteCount > 0,
        ) {
            Icon(Icons.Default.Favorite, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                if (favoriteCount > 0) "Play favorites" else "No favorites",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(
            onClick = onResume,
            modifier = Modifier.weight(1f),
            enabled = canResume,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(resumeLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderLegend(
    localState: ProviderState,
    spotifyState: ProviderState,
    youtubeState: ProviderState,
    onOpenSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.then(
            if (onOpenSettings != null) Modifier.clickable(onClick = onOpenSettings) else Modifier,
        ),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        LegendItem(ProviderId.LOCAL, localState)
        LegendItem(ProviderId.SPOTIFY, spotifyState)
        LegendItem(ProviderId.YOUTUBE_MUSIC, youtubeState)
    }
}

@Composable
private fun LegendItem(provider: ProviderId, state: ProviderState) {
    val shortName = when (provider) {
        ProviderId.LOCAL -> "Local"
        ProviderId.SPOTIFY -> "Spotify"
        ProviderId.YOUTUBE_MUSIC -> "YouTube"
        ProviderId.SAMPLE -> provider.displayName
    }
    val stateWord = legendStateWord(state)
    Row(
        Modifier.heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(provider, state)
        Spacer(Modifier.width(6.dp))
        Text(
            buildString {
                append(shortName)
                if (stateWord != null) {
                    append(" · ")
                    append(stateWord)
                }
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

@Composable
private fun HeroTrackBlock(
    hero: HomeHero.TrackHero,
    onPlay: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = hero.track
    val playable = track.playableSources().firstOrNull()
    val meta = buildString {
        if (playable != null) {
            append(shortProviderName(playable.provider))
        } else {
            append("Needs connection")
        }
        track.durationMs?.takeIf { it > 0 }?.let {
            if (isNotEmpty()) append(" · ")
            append(formatTime(it))
        }
    }
    BoxWithConstraints(modifier) {
        val artSize = if (maxWidth >= 600.dp) 120.dp else 96.dp
        Surface(
            onClick = if (hero.playingNow) onOpenNowPlaying else onPlay,
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtworkImage(
                    track.artwork,
                    track.title,
                    Modifier.size(artSize),
                    track.title,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (hero.playingNow) "Playing now" else "Last played",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        track.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        track.artistLine,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                if (hero.playingNow) {
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowRight,
                        contentDescription = "Open now playing",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FilledIconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroLibraryBlock(
    trackCount: Int,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onPlay,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(40.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "Your library",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "$trackCount local tracks",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "Play all from the start",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(onClick = onPlay, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play library")
            }
        }
    }
}

@Composable
private fun DiscoverBlock(
    playlist: Playlist,
    hero: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val artSize = if (hero) 96.dp else 72.dp
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
            Modifier.size(artSize),
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
            if (hero) {
                playlist.description?.takeIf { it.isNotBlank() }?.let { description ->
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun DiscoverWeeklyPlaceholder(
    loading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = !loading, onClick = onRefresh)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Default.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                "New this week",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text("Discover Weekly", style = MaterialTheme.typography.titleMedium)
            Text(
                if (loading) {
                    "Looking in your Spotify library…"
                } else {
                    "Add Discover Weekly to your Spotify library, or paste the share link in Settings, then refresh"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
