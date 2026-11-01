package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.library.folderPinDisplayName
import com.universalmusic.player.data.library.requiresNetworkToPlay
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.model.Artwork
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

internal const val CONTINUE_LISTENING_ROWS = 6

internal fun continueListeningLimit(columns: Int): Int = maxOf(8, columns * CONTINUE_LISTENING_ROWS)

internal fun continueListeningTracks(
    recent: List<Track>,
    sessionFocus: SessionFocus?,
    playerPaneVisible: Boolean,
    limit: Int = CONTINUE_LISTENING_ROWS * 2,
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
        .take(limit)
        .toList()
}

/** Columns for the recents ledger: rows are about 360 dp wide before they stop reading as a ledger. */
internal fun continueListeningColumns(widthDp: Int): Int = when {
    widthDp >= 1200 -> 3
    widthDp >= 760 -> 2
    else -> 1
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

internal fun legendStateWord(state: ProviderState): String? = when (state) {
    ProviderState.LOADING -> "Connecting"
    ProviderState.AUTH_REQUIRED -> "Sign in"
    ProviderState.NOT_CONFIGURED -> "Not set up"
    ProviderState.UNAVAILABLE -> "Offline"
    ProviderState.RATE_LIMITED -> "Limited"
    ProviderState.AVAILABLE -> null
}

internal data class FavoritesSummary(
    val count: Int,
    val local: Int,
    val spotify: Int,
    val youtube: Int,
    val covers: List<Artwork>,
) {
    companion object {
        val EMPTY = FavoritesSummary(0, 0, 0, 0, emptyList())
    }
}

internal const val FAVORITES_HERO_COVERS = 4

internal fun favoritesSummary(favoriteIds: Set<String>, catalog: List<Track>): FavoritesSummary {
    if (favoriteIds.isEmpty()) return FavoritesSummary.EMPTY
    val byId = catalog.associateBy { it.canonicalId }
    val covers = favoriteIds.sortedDescending()
        .asSequence()
        .mapNotNull { byId[it]?.artwork }
        .distinctBy { it.url }
        .take(FAVORITES_HERO_COVERS)
        .toList()
    var spotify = 0
    var youtube = 0
    favoriteIds.forEach { id ->
        when {
            id.startsWith("spotify:") -> spotify += 1
            id.startsWith("yt:") -> youtube += 1
        }
    }
    return FavoritesSummary(
        count = favoriteIds.size,
        local = favoriteIds.size - spotify - youtube,
        spotify = spotify,
        youtube = youtube,
        covers = covers,
    )
}

internal fun homeEyebrow(favorites: Int, localTracks: Int): String {
    val parts = buildList {
        if (favorites > 0) add("$favorites hearted")
        if (localTracks > 0) add("$localTracks local ${if (localTracks == 1) "song" else "songs"}")
    }
    return if (parts.isEmpty()) "Your listening" else parts.joinToString(" · ")
}

/** Rows of the "Your library" ledger. Each opens the place where the listener acts on it. */
internal enum class LibraryMark { LOCAL, HEARTS, PLAYLISTS, SPOTIFY, YOUTUBE }

internal enum class LibraryDoor { LIBRARY, FAVORITES, SETTINGS, SEARCH, PICK_FOLDER, RETRY_LOCAL }

internal data class LibraryLine(
    val mark: LibraryMark,
    val title: String,
    val detail: String,
    val door: LibraryDoor,
    val settingsTarget: SettingsTarget? = null,
)

private fun tracksLabel(count: Int) = if (count == 1) "1 track" else "$count tracks"

internal fun libraryLines(
    sources: List<SourceReadiness>,
    favorites: FavoritesSummary,
    kainosPlaylistCount: Int,
): List<LibraryLine> = buildList {
    fun sourceLine(source: MusicSource, mark: LibraryMark): LibraryLine {
        val readiness = sources.first { it.source == source }
        val action = readiness.step?.action
        return LibraryLine(
            mark,
            source.title,
            if (source == MusicSource.Local && readiness.status == SourceStatus.Trouble) "${readiness.detail} · Retry"
            else if (source == MusicSource.Local && readiness.status == SourceStatus.Ready) readiness.detail.removeSuffix(".")
            else listOfNotNull(readiness.statusLabel, readiness.step?.label).joinToString(" · "),
            when (action) {
                SourceAction.RetryLocal -> LibraryDoor.RETRY_LOCAL
                SourceAction.PickMusicFolder -> LibraryDoor.PICK_FOLDER
                SourceAction.OpenLibrary, null -> LibraryDoor.LIBRARY
                SourceAction.OpenSearch -> LibraryDoor.SEARCH
                is SourceAction.OpenSettings -> LibraryDoor.SETTINGS
            },
            (action as? SourceAction.OpenSettings)?.target,
        )
    }
    add(sourceLine(MusicSource.Local, LibraryMark.LOCAL))
    add(
        if (favorites.count > 0) {
            LibraryLine(LibraryMark.HEARTS, "Hearted", "${favorites.count} ${if (favorites.count == 1) "song" else "songs"} · Play favorites", LibraryDoor.FAVORITES)
        } else {
            LibraryLine(LibraryMark.HEARTS, "Hearted", "Heart songs in Now Playing to build favorites", LibraryDoor.LIBRARY)
        },
    )
    if (kainosPlaylistCount > 0) {
        add(
            LibraryLine(
                LibraryMark.PLAYLISTS,
                "Playlists",
                "$kainosPlaylistCount Kainos ${if (kainosPlaylistCount == 1) "playlist" else "playlists"}",
                LibraryDoor.LIBRARY,
            ),
        )
    }
    add(sourceLine(MusicSource.Spotify, LibraryMark.SPOTIFY))
    add(sourceLine(MusicSource.YouTube, LibraryMark.YOUTUBE))
}

/** Something from the library the listener could pin, offered on Home while pins are few. */
internal data class PinSuggestion(
    val kind: HomePinKind,
    val targetId: String,
    val title: String,
    val subtitle: String,
    val artwork: Artwork?,
    val providerEntityId: String? = null,
) {
    /** A suggestion plays through the same path as a pin, so the two never drift apart. */
    fun asResolvedPin(): ResolvedHomePin = ResolvedHomePin(
        PersistedHomePin(
            id = "suggestion:${kind.name}:$targetId",
            kind = kind,
            targetId = targetId,
            title = title,
            subtitle = subtitle,
            artworkUrl = artwork?.url,
            providerEntityId = providerEntityId,
        ),
        PinStatus.READY,
        subtitle,
    )
}

internal const val PIN_SUGGESTION_LIMIT = 6

/** Suggestions stop once the listener has pinned a few things of their own. */
internal const val PIN_SUGGESTIONS_UNTIL_PINS = 3

internal fun pinSuggestions(
    homePins: List<PersistedHomePin>,
    kainosPlaylists: List<PersistedKainosPlaylist>,
    spotifyPlaylists: List<Playlist>,
    spotifyState: ProviderState,
    localTracks: List<Track>,
    folders: List<String>,
    folderTrackCounts: Map<String, Int>,
    limit: Int = PIN_SUGGESTION_LIMIT,
): List<PinSuggestion> {
    if (homePins.size >= PIN_SUGGESTIONS_UNTIL_PINS) return emptyList()
    val pinned = homePins.map { it.kind to it.targetId }.toSet()
    val candidates = buildList {
        kainosPlaylists
            .filter { it.entries.isNotEmpty() }
            .sortedByDescending { it.updatedAtMs }
            .forEach { playlist ->
                val art = playlist.entries.firstNotNullOfOrNull { it.track.artworkUrl }?.let { Artwork(it) }
                add(
                    PinSuggestion(
                        HomePinKind.KAINOS_PLAYLIST,
                        playlist.id,
                        playlist.title,
                        "Kainos · ${tracksLabel(playlist.entries.size)}",
                        art,
                    ),
                )
            }
        folders.forEach { folder ->
            val count = folderTrackCounts[folder] ?: 0
            if (count > 0) {
                add(PinSuggestion(HomePinKind.LOCAL_FOLDER, folder, folderPinDisplayName(folder), "Folder · ${tracksLabel(count)}", null))
            }
        }
        localTracks
            .filter { it.album != null }
            .groupBy { it.album!!.canonicalId }
            .values
            .sortedWith(compareByDescending<List<Track>> { it.size }.thenBy { it.first().album!!.title })
            .forEach { tracks ->
                val album = tracks.first().album!!
                add(
                    PinSuggestion(
                        HomePinKind.ALBUM,
                        album.canonicalId,
                        album.title,
                        "Album · ${tracks.size} ${if (tracks.size == 1) "track" else "tracks"}",
                        album.artwork ?: tracks.firstNotNullOfOrNull { it.artwork },
                    ),
                )
            }
        if (spotifyState == ProviderState.AVAILABLE) {
            spotifyPlaylists.forEach { playlist ->
                add(
                    PinSuggestion(
                        HomePinKind.PROVIDER_PLAYLIST,
                        playlist.canonicalId,
                        playlist.title,
                        playlist.trackCount?.let { "Spotify · $it tracks" } ?: "Spotify playlist",
                        playlist.artwork,
                        providerEntityId = playlist.source.providerEntityId,
                    ),
                )
            }
        }
    }
    return candidates
        .filter { (it.kind to it.targetId) !in pinned }
        .take(limit)
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
                    "Kainos · ${tracksLabel(count)}",
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
                    "Album · ${tracksLabel(count)}",
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
                    ResolvedHomePin(pin.copy(title = title), PinStatus.READY, "Folder · ${tracksLabel(count)}")
                }
            }
        }
    }
}
