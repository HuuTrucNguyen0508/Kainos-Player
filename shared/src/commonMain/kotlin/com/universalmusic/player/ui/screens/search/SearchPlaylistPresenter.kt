package com.universalmusic.player.ui.screens

import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.PlaylistTracksPage
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where the next page of a provider playlist starts. */
internal sealed interface PlaylistCursor {
    data object First : PlaylistCursor
    data class After(val token: String) : PlaylistCursor
    data object End : PlaylistCursor
}

internal data class PlaylistPageState(
    val playlist: Playlist,
    val tracks: List<Track> = emptyList(),
    val total: Int? = playlist.trackCount,
    val cursor: PlaylistCursor = PlaylistCursor.First,
    val loading: Boolean = false,
    /** Why the last page failed; the songs already loaded stay. */
    val error: String? = null,
    /** Play or Shuffle is fetching the remaining pages before it starts. */
    val preparingPlay: Boolean = false,
) {
    val complete: Boolean get() = cursor == PlaylistCursor.End
}

/**
 * A Spotify or YouTube playlist opened from Search. Pages load as the list scrolls; Play and
 * Shuffle fetch the rest first so the queue is the whole playlist. A failed page keeps the songs
 * already in, and the next [loadMore] retries it.
 */
internal class SearchPlaylistPresenter(
    playlist: Playlist,
    private val loadPage: suspend (ProviderEntityRef, String?) -> PlaylistTracksPage,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(PlaylistPageState(playlist))
    val state: StateFlow<PlaylistPageState> = mutableState.asStateFlow()
    private val pages = Mutex()
    private var fetching: Job? = null

    init {
        loadMore()
    }

    /** Asks for the next page unless one is already on its way or every page is in. */
    fun loadMore() {
        if (fetching?.isActive == true || state.value.complete) return
        fetching = scope.launch { fetchNext() }
    }

    /**
     * Loads every remaining page, then hands the songs to [onReady] with [startIndex]. If a page
     * fails, it plays what loaded and the page shows the error.
     */
    fun play(startIndex: Int, shuffle: Boolean, onReady: (List<Track>, Int, Boolean) -> Unit) {
        if (state.value.preparingPlay) return
        scope.launch {
            mutableState.update { it.copy(preparingPlay = true) }
            try {
                while (!state.value.complete && fetchNext()) Unit
            } finally {
                mutableState.update { it.copy(preparingPlay = false) }
            }
            val tracks = state.value.tracks
            if (tracks.isNotEmpty()) onReady(tracks, startIndex.coerceIn(0, tracks.lastIndex), shuffle)
        }
    }

    /** Fetches one page; false when it failed. */
    private suspend fun fetchNext(): Boolean = pages.withLock {
        val current = mutableState.value
        val token = when (val cursor = current.cursor) {
            PlaylistCursor.First -> null
            is PlaylistCursor.After -> cursor.token
            PlaylistCursor.End -> return@withLock true
        }
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val page = withContext(dispatcher) { loadPage(current.playlist.source, token) }
            mutableState.update {
                it.copy(
                    tracks = it.tracks + page.tracks,
                    total = page.total ?: it.total,
                    cursor = page.next?.let(PlaylistCursor::After) ?: PlaylistCursor.End,
                    loading = false,
                )
            }
            true
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(loading = false) }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update {
                it.copy(loading = false, error = failure.message ?: "${current.playlist.source.provider.displayName} did not answer.")
            }
            false
        }
    }
}
