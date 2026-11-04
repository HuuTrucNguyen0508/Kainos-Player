package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.PlayerSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A short confirmation after a row action; [id] lets the notice bar time out the right one. */
internal data class TrackActionNotice(val id: Long, val message: String)

/**
 * The actions behind a track row's menu. Hearts go through the same library call Now Playing
 * uses, so account-scoped Spotify hearts, heart downloads and Home sync heart ops all follow.
 */
internal class TrackActionsPresenter(
    val favorites: StateFlow<Set<String>>,
    val playlists: StateFlow<List<PersistedKainosPlaylist>>,
    private val playNextAction: (Track) -> Unit,
    private val addToQueueAction: (Track) -> Unit,
    private val toggleFavoriteTrack: (Track) -> Boolean,
    private val syncNowPlayingFavorite: (canonicalId: String, favorite: Boolean) -> Unit,
    private val addTracksToPlaylist: (String, List<Track>) -> PersistedKainosPlaylist?,
    private val createPlaylistWith: (String, List<Track>) -> PersistedKainosPlaylist,
) {
    private var nextNoticeId = 0L
    private val _notice = MutableStateFlow<TrackActionNotice?>(null)
    val notice: StateFlow<TrackActionNotice?> = _notice.asStateFlow()

    /** Rows that cannot play in-app would only be skipped by the queue. */
    fun canQueue(track: Track): Boolean = track.sources.any { it.isPlayable }

    fun playNext(track: Track) {
        if (!canQueue(track)) return
        playNextAction(track)
        announce("Playing next: ${track.title}")
    }

    fun addToQueue(track: Track) {
        if (!canQueue(track)) return
        addToQueueAction(track)
        announce("Added to queue: ${track.title}")
    }

    fun toggleFavorite(track: Track) {
        val favorite = toggleFavoriteTrack(track)
        // Only moves the Now Playing heart when this row is the current track.
        syncNowPlayingFavorite(track.canonicalId, favorite)
        announce(if (favorite) "Hearted ${track.title}" else "Removed heart from ${track.title}")
    }

    fun addToPlaylist(playlistId: String, track: Track) {
        val before = playlists.value.firstOrNull { it.id == playlistId } ?: return
        if (before.entries.any { it.track.canonicalId == track.canonicalId }) {
            announce("Already in ${before.title}")
            return
        }
        val after = addTracksToPlaylist(playlistId, listOf(track)) ?: return
        announce("Added to ${after.title}")
    }

    fun createPlaylist(name: String, track: Track) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val created = createPlaylistWith(trimmed, listOf(track))
        announce("Added to ${created.title}")
    }

    fun dismissNotice(id: Long) {
        _notice.update { if (it?.id == id) null else it }
    }

    private fun announce(message: String) {
        _notice.value = TrackActionNotice(++nextNoticeId, message)
    }

    companion object {
        fun from(container: AppContainer): TrackActionsPresenter =
            from(container.library, container.player, container.kainosPlaylists)

        fun from(
            library: LibraryRepository,
            player: PlayerSession,
            playlists: KainosPlaylistRepository,
        ): TrackActionsPresenter = TrackActionsPresenter(
            favorites = library.favoriteIds,
            playlists = playlists.playlists,
            playNextAction = player::playNext,
            addToQueueAction = player::addToQueue,
            toggleFavoriteTrack = library::toggleFavorite,
            syncNowPlayingFavorite = player::syncFavorite,
            addTracksToPlaylist = { id, tracks -> playlists.addTracks(id, tracks) },
            createPlaylistWith = { name, tracks -> playlists.create(name, tracks) },
        )
    }
}
