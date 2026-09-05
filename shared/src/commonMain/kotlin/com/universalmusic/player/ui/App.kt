package com.universalmusic.player.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.app.UiRequest
import com.universalmusic.player.app.ensureAppContainer
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.navigation.AppDestination
import com.universalmusic.player.ui.screens.HomeScreen
import com.universalmusic.player.ui.screens.LibraryScreen
import com.universalmusic.player.ui.screens.NowPlayingScreen
import com.universalmusic.player.ui.screens.QueueScreen
import com.universalmusic.player.ui.screens.SearchScreen
import com.universalmusic.player.ui.screens.SettingsScreen
import com.universalmusic.player.ui.theme.UniversalMusicTheme
import com.universalmusic.player.ui.components.MiniPlayerBar

@Composable
fun UniversalMusicApp(container: AppContainer = ensureAppContainer()) {
    val settings by container.settings.collectAsState()
    UniversalMusicTheme(settings.themeMode) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            AppScaffold(container, desktop = maxWidth >= 840.dp)
        }
    }
}

@Composable
private fun AppScaffold(container: AppContainer, desktop: Boolean) {
    var destination by remember { mutableStateOf(AppDestination.Home) }
    var showNowPlaying by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val now by container.player.nowPlaying.collectAsState()

    fun play(track: Track) {
        container.library.recordPlay(track)
        container.player.play(track)
        showNowPlaying = true
    }

    fun playTracks(tracks: List<Track>) {
        val first = tracks.firstOrNull() ?: return
        container.library.recordPlay(first)
        container.player.play(tracks)
        showNowPlaying = true
    }

    // Platform shells (desktop keyboard shortcuts) drive navigation through this flow.
    LaunchedEffect(container) {
        container.uiRequests.collect { request ->
            when (request) {
                UiRequest.FOCUS_SEARCH -> {
                    destination = AppDestination.Search
                    showNowPlaying = false
                    showQueue = false
                }
                UiRequest.TOGGLE_QUEUE -> showQueue = !showQueue
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (!desktop && !showNowPlaying && !showQueue) {
                Column {
                    now.track?.let { track ->
                        MiniPlayerBar(
                            title = track.title,
                            artist = track.artistLine,
                            artwork = track.artwork,
                            isPlaying = now.isPlaying,
                            providerLabel = now.resolved?.source?.provider?.displayName,
                            onOpen = { showNowPlaying = true },
                            onToggle = { container.player.togglePlayPause() },
                            onNext = { container.player.skipToNext() },
                        )
                    }
                    NavigationBar {
                        AppDestination.entries.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item,
                                onClick = {
                                    destination = item
                                    showNowPlaying = false
                                    showQueue = false
                                },
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
                NavigationRail {
                    AppDestination.entries.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item && !showQueue,
                            onClick = {
                                destination = item
                                showNowPlaying = false
                                showQueue = false
                            },
                            icon = { Icon(item.icon(), contentDescription = item.label) },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) {
                when {
                    showQueue && !desktop -> QueueScreen(container) { showQueue = false }
                    showNowPlaying && !desktop -> NowPlayingScreen(
                        container,
                        onOpenQueue = { showQueue = true },
                        onClose = { showNowPlaying = false },
                    )
                    else -> when (destination) {
                        AppDestination.Home -> HomeScreen(
                            container,
                            onPlay = ::play,
                            onPlayTracks = ::playTracks,
                            onOpenNowPlaying = { showNowPlaying = true },
                        )
                        AppDestination.Search -> SearchScreen(container, ::play, requestFocus = true)
                        AppDestination.Library -> LibraryScreen(container, ::play, ::playTracks)
                        AppDestination.Settings -> SettingsScreen(container)
                    }
                }
            }
            if (desktop) {
                Surface(Modifier.widthIn(min = 360.dp, max = 420.dp).fillMaxSize(), tonalElevation = 1.dp) {
                    if (showQueue) {
                        QueueScreen(container) { showQueue = false }
                    } else {
                        NowPlayingScreen(container, onOpenQueue = { showQueue = true }, compact = true)
                    }
                }
            }
        }
    }
}

private fun AppDestination.icon() = when (this) {
    AppDestination.Home -> Icons.Default.Home
    AppDestination.Search -> Icons.Default.Search
    AppDestination.Library -> Icons.Default.LibraryMusic
    AppDestination.Settings -> Icons.Default.Settings
}
