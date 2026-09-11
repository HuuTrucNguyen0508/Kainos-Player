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
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.library.requiresNetworkToPlay
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.ArtworkImage
import com.universalmusic.player.ui.theme.providerColor
import kotlinx.coroutines.launch

private sealed class HomeHero {
    data class TrackHero(val track: Track, val queue: List<Track>, val playingNow: Boolean) : HomeHero()
    data class Discover(val playlist: Playlist) : HomeHero()
    data class Library(val tracks: List<Track>) : HomeHero()
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
    val spotifyState by container.spotify.state.collectAsState()
    val youtubeState by container.youtube.state.collectAsState()
    val localState by container.local.state.collectAsState()
    val localTracks by container.local.libraryTracks.collectAsState()
    val discoverWeekly by container.spotifyDiscoverWeekly.collectAsState()
    val now by container.player.nowPlaying.collectAsState()
    val spotifyLoading by container.spotifyLibraryLoading.collectAsState()
    val scope = rememberCoroutineScope()
    var discoverBusy by remember { mutableStateOf(false) }
    var discoverError by remember { mutableStateOf<String?>(null) }

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
    val ledger = remember(recent, hero) {
        when (hero) {
            is HomeHero.TrackHero -> recent.drop(1).take(8)
            else -> recent.take(8)
        }
    }
    val empty = recent.isEmpty() && localTracks.isEmpty() && discoverWeekly == null
    val spotifyConnected = spotifyState == ProviderState.AVAILABLE || spotifyState == ProviderState.RATE_LIMITED
    val showDiscoverSlot = discoverWeekly != null ||
        (spotifyConnected && hero !is HomeHero.Discover)

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
                Spacer(Modifier.height(12.dp))
            }

            when (val current = hero) {
                is HomeHero.TrackHero -> HeroTrackBlock(
                    hero = current,
                    onPlay = {
                        onPlayTracks(current.queue, 0)
                        onOpenNowPlaying()
                    },
                    onOpenNowPlaying = onOpenNowPlaying,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                is HomeHero.Discover -> DiscoverBlock(
                    playlist = current.playlist,
                    hero = true,
                    busy = discoverBusy,
                    onClick = { playDiscover(current.playlist) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                is HomeHero.Library -> HeroLibraryBlock(
                    trackCount = current.tracks.size,
                    onPlay = {
                        onPlayTracks(current.tracks, 0)
                        onOpenNowPlaying()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
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

            if (ledger.isNotEmpty()) {
                Text(
                    "Earlier",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp),
                )
                ledger.forEachIndexed { index, track ->
                    LedgerRow(
                        track = track,
                        onClick = { onPlayTracks(ledger, index) },
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
