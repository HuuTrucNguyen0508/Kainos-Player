package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.library.folderPinDisplayName
import com.universalmusic.player.data.library.requiresNetworkToPlay
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track

internal data class ResolvedHomePin(
    val pin: PersistedHomePin,
    val status: PinStatus,
    val detail: String,
)

internal enum class PinStatus {
    READY,
    EMPTY,
    MISSING,
    LOADING,
    DISCONNECTED,
    RATE_LIMITED,
    ERROR,
}

internal sealed class SessionFocus {
    data class Restored(val track: Track, val isPlaying: Boolean) : SessionFocus()
    data class LastPlayed(val track: Track, val queue: List<Track>) : SessionFocus()
}

internal fun sessionFocusOf(
    track: Track?,
    isPlaying: Boolean,
    recent: List<Track>,
): SessionFocus? = when {
    track != null -> SessionFocus.Restored(track, isPlaying)
    recent.isNotEmpty() -> SessionFocus.LastPlayed(recent.first(), recent)
    else -> null
}

/** Desktop hides the session card while the Now Playing pane is open. */
internal fun homeSessionCardVisible(playerPaneVisible: Boolean, sessionFocus: SessionFocus?): Boolean =
    !playerPaneVisible && sessionFocus != null

internal fun continueListeningTracks(
    recent: List<Track>,
    sessionFocus: SessionFocus?,
    playerPaneVisible: Boolean,
): List<Track> {
    val excludeId = when {
        playerPaneVisible -> null
        sessionFocus is SessionFocus.Restored -> sessionFocus.track.canonicalId
        sessionFocus is SessionFocus.LastPlayed -> sessionFocus.track.canonicalId
        else -> null
    }
    return recent
        .asSequence()
        .filter { excludeId == null || it.canonicalId != excludeId }
        .take(8)
        .toList()
}

internal fun loadedDiscoverPlaylist(playlist: Playlist?): Playlist? =
    playlist?.takeIf { it.tracks.isNotEmpty() || (it.trackCount ?: 0) > 0 }

internal fun homeIsEmpty(
    recent: List<Track>,
    localTracks: List<Track>,
    loadedDiscover: Playlist?,
    homePins: List<PersistedHomePin>,
    favorites: Set<String>,
    nowTrack: Track?,
): Boolean = recent.isEmpty() &&
    localTracks.isEmpty() &&
    loadedDiscover == null &&
    homePins.isEmpty() &&
    favorites.isEmpty() &&
    nowTrack == null

internal fun configuredMusicFolders(
    localMusicFolders: List<String>,
    localMusicFoldersConfigured: Boolean,
    defaultFolder: String,
): List<String> = if (localMusicFoldersConfigured) {
    localMusicFolders
} else {
    listOfNotNull(defaultFolder.takeIf { it.isNotBlank() })
}

internal fun folderTrackCounts(
    folders: List<String>,
    tracksInFolder: (String) -> List<Track>,
): Map<String, Int> = folders.associateWith { folder -> tracksInFolder(folder).size }

internal fun lastPlayedStartIndex(queue: List<Track>, track: Track): Int =
    queue.indexOfFirst { it.canonicalId == track.canonicalId }.coerceAtLeast(0)

internal fun sessionEyebrow(focus: SessionFocus): String = when (focus) {
    is SessionFocus.Restored -> if (focus.isPlaying) "Playing" else "Resume"
    is SessionFocus.LastPlayed -> "Last played"
}

internal fun sessionSourceLabel(track: Track): String {
    val playable = track.playableSources().firstOrNull()
    return playable?.provider?.let { shortProviderName(it) } ?: "Needs connection"
}

internal fun shortProviderName(provider: ProviderId): String = when (provider) {
    ProviderId.LOCAL -> "Local"
    ProviderId.SPOTIFY -> "Spotify"
    ProviderId.YOUTUBE_MUSIC -> "YouTube"
    ProviderId.SAMPLE -> provider.displayName
}

internal fun ledgerArtistLine(track: Track): String {
    val playable = track.playableSources().firstOrNull()
    val needsConnection = track.requiresNetworkToPlay() && playable == null
    return buildString {
        append(track.artistLine)
        if (needsConnection) append(" · Needs connection")
    }
}

internal fun ledgerSourceLabel(track: Track): String {
    val playable = track.playableSources().firstOrNull()
    return playable?.provider?.let { "Played from ${it.displayName}" } ?: "Not playable right now"
}

internal fun providerAttentionMessage(
    localState: ProviderState,
    spotifyState: ProviderState,
    youtubeState: ProviderState,
): String? {
    val attention = listOf(
        ProviderId.SPOTIFY to spotifyState,
        ProviderId.YOUTUBE_MUSIC to youtubeState,
        ProviderId.LOCAL to localState,
    ).firstOrNull { (_, state) ->
        state != ProviderState.AVAILABLE && state != ProviderState.LOADING
    } ?: return null

    val (provider, state) = attention
    val shortName = when (provider) {
        ProviderId.LOCAL -> "Local library"
        ProviderId.SPOTIFY -> "Spotify"
        ProviderId.YOUTUBE_MUSIC -> "YouTube"
        ProviderId.SAMPLE -> provider.displayName
    }
    return when (state) {
        ProviderState.AUTH_REQUIRED -> "$shortName needs sign-in · Open Settings"
        ProviderState.NOT_CONFIGURED -> "$shortName not set up · Open Settings"
        ProviderState.UNAVAILABLE -> "$shortName is offline · Open Settings"
        ProviderState.RATE_LIMITED -> "$shortName is limited · Try again soon"
        else -> {
            val word = legendStateWord(state) ?: return null
            "$shortName · $word · Open Settings"
        }
    }
}

internal fun legendStateWord(state: ProviderState): String? = when (state) {
    ProviderState.LOADING -> "Connecting"
    ProviderState.AUTH_REQUIRED -> "Sign in"
    ProviderState.NOT_CONFIGURED -> "Not set up"
    ProviderState.UNAVAILABLE -> "Offline"
    ProviderState.RATE_LIMITED -> "Limited"
    ProviderState.AVAILABLE -> null
}

internal fun resolveHomePins(
    homePins: List<PersistedHomePin>,
    kainosPlaylists: List<PersistedKainosPlaylist>,
    spotifyPlaylists: List<Playlist>,
    localTracks: List<Track>,
    folders: List<String>,
    folderTrackCounts: Map<String, Int>,
    spotifyState: ProviderState,
    busyPinId: String?,
): List<ResolvedHomePin> {
    val kainosTitles = kainosPlaylists.associate { it.id to it.title }
    val kainosTrackCounts = kainosPlaylists.associate { it.id to it.entries.size }
    val albumTrackCounts = localTracks
        .mapNotNull { t -> t.album?.canonicalId?.let { id -> id to t } }
        .groupBy({ it.first }, { it.second })
        .mapValues { it.value.size }
    val albumTitles = localTracks.mapNotNull { t -> t.album?.let { it.canonicalId to it.title } }.toMap()
    return homePins.map { pin ->
        resolveHomePin(
            pin = pin,
            kainosTitles = kainosTitles,
            kainosTrackCounts = kainosTrackCounts,
            spotifyPlaylists = spotifyPlaylists,
            albumTrackCounts = albumTrackCounts,
            albumTitles = albumTitles,
            folders = folders,
            folderTrackCounts = folderTrackCounts,
            spotifyState = spotifyState,
            busyPinId = busyPinId,
        )
    }
}

internal fun resolveHomePin(
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
