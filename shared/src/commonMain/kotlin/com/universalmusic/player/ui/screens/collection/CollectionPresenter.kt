package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.playlist.PersistedPlaylistEntry
import com.universalmusic.player.domain.model.Album
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.ui.components.SourceKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class CollectionKind { Album, Artist, Playlist }

/** What the top of a collection page says about it. */
internal data class CollectionHeader(
    val kind: CollectionKind,
    val title: String,
    val source: SourceKind,
    /** "Spotify album", "Kainos playlist", "Artist in your library". */
    val sourceLabel: String,
    val artwork: Artwork? = null,
    /** Album artists or the playlist's owner. */
    val byline: String? = null,
    /** Where tapping the byline goes: an album's artist. */
    val bylineRef: CollectionRef? = null,
    val year: Int? = null,
    /** The page on Spotify or YouTube, for Open on …. */
    val webUrl: String? = null,
    /** Songs the source says it has, which may be more than it can show. */
    val trackCount: Int? = null,
)

/** Where the next page of songs starts. */
internal sealed interface PlaylistCursor {
    data object First : PlaylistCursor
    data class After(val token: String) : PlaylistCursor
    data object End : PlaylistCursor
}

/** Part of a page as a source delivers it. Each field it leaves at its default keeps what the page had. */
internal data class CollectionChunk(
    val tracks: List<Track>,
    val next: String? = null,
    val total: Int? = null,
    val header: CollectionHeader? = null,
    val albums: List<Album> = emptyList(),
    /** Something the listener should know about this page, such as a part Spotify would not share. */
    val notice: String? = null,
    /** A Kainos playlist's entry ids, one per song in order; only these pages can be edited. */
    val entryIds: List<String>? = null,
)

internal sealed interface CollectionSource {
    /** Songs fetched a page at a time on demand: provider playlists, and Spotify albums and artists in one page. */
    fun interface Paged : CollectionSource {
        suspend fun page(cursor: String?): CollectionChunk
    }

    /** Content that follows the app's own state (the library, Kainos playlists): each emission is the whole page. */
    class Live(val content: Flow<CollectionChunk>) : CollectionSource
}

/** Changes a Kainos playlist page can make. */
internal class PlaylistEditor(
    /** Removes the entry and returns it with its position, or null when it was already gone. */
    val remove: (entryId: String) -> Pair<PersistedPlaylistEntry, Int>?,
    val restore: (PersistedPlaylistEntry, Int) -> Unit,
    val reorder: (List<String>) -> Unit,
    /** Adds the songs now in the queue; returns how many. */
    val addCurrentQueue: () -> Int,
)

internal data class CollectionState(
    val header: CollectionHeader,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val total: Int? = header.trackCount,
    val cursor: PlaylistCursor = PlaylistCursor.First,
    val loading: Boolean = false,
    /** Why the last page failed; the songs already loaded stay. */
    val error: String? = null,
    val notice: String? = null,
    /** Play or Shuffle is fetching the remaining pages before it starts. */
    val preparingPlay: Boolean = false,
    val entryIds: List<String>? = null,
    /** The last removed song, until it is put back or another is removed. */
    val removed: RemovedEntry? = null,
) {
    val complete: Boolean get() = cursor == PlaylistCursor.End
    val editable: Boolean get() = entryIds != null

    /** Every song's length added up, once every song is in and each has one. */
    val totalDurationMs: Long?
        get() = if (!complete || tracks.isEmpty() || tracks.any { (it.durationMs ?: 0) <= 0 }) null
        else tracks.sumOf { it.durationMs ?: 0 }
}

internal data class RemovedEntry(val entry: PersistedPlaylistEntry, val index: Int, val title: String)

/**
 * One album, artist or playlist page. Paged sources load as the list scrolls; Play and Shuffle fetch
 * the rest first so the queue is the whole collection. A failed page keeps the songs already in, and
 * the next [loadMore] or Play retries it. Live sources stay current with the library on their own.
 */
internal class CollectionPresenter(
    header: CollectionHeader,
    private val source: CollectionSource,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val editor: PlaylistEditor? = null,
) {
    private val mutableState = MutableStateFlow(CollectionState(header))
    val state: StateFlow<CollectionState> = mutableState.asStateFlow()
    private val pages = Mutex()
    private var fetching: Job? = null
    private var fetchingFrom: PlaylistCursor? = null

    init {
        when (source) {
            is CollectionSource.Paged -> loadMore()
            is CollectionSource.Live -> scope.launch {
                source.content.collect { chunk ->
                    mutableState.update {
                        it.copy(
                            header = chunk.header ?: it.header,
                            tracks = chunk.tracks,
                            albums = chunk.albums,
                            total = chunk.tracks.size,
                            cursor = PlaylistCursor.End,
                            notice = chunk.notice,
                            entryIds = chunk.entryIds,
                        )
                    }
                }
            }
        }
    }

    /**
     * Asks for the page at the current cursor unless that page is already on its way or every page
     * is in. A fetch whose page has landed can still be finishing, so only the same cursor is skipped.
     */
    fun loadMore() {
        if (source !is CollectionSource.Paged) return
        val cursor = state.value.cursor
        if (cursor == PlaylistCursor.End || (fetching?.isActive == true && fetchingFrom == cursor)) return
        fetchingFrom = cursor
        fetching = scope.launch { fetchNext(source) }
    }

    /**
     * Loads every remaining page, then hands the songs to [onReady] with [startIndex]. If a page
     * fails, nothing plays: the page keeps the songs it has and shows the error with Try again.
     */
    fun play(startIndex: Int, shuffle: Boolean, onReady: (List<Track>, Int, Boolean) -> Unit) {
        if (state.value.preparingPlay) return
        scope.launch {
            if (source is CollectionSource.Paged) {
                mutableState.update { it.copy(preparingPlay = true) }
                try {
                    while (!state.value.complete && fetchNext(source)) Unit
                } finally {
                    mutableState.update { it.copy(preparingPlay = false) }
                }
            }
            val tracks = state.value.tracks
            if (state.value.complete && tracks.isNotEmpty()) onReady(tracks, startIndex.coerceIn(0, tracks.lastIndex), shuffle)
        }
    }

    /** Takes the song at [index] out of a Kainos playlist; [undoRemove] puts it back. */
    fun remove(index: Int) {
        val editor = editor ?: return
        val current = state.value
        val entryId = current.entryIds?.getOrNull(index) ?: return
        val title = current.tracks.getOrNull(index)?.title.orEmpty()
        val (entry, at) = editor.remove(entryId) ?: return
        mutableState.update { it.copy(removed = RemovedEntry(entry, at, title)) }
    }

    fun undoRemove() {
        val editor = editor ?: return
        val removed = state.value.removed ?: return
        editor.restore(removed.entry, removed.index)
        mutableState.update { it.copy(removed = null) }
    }

    fun dismissUndo() {
        mutableState.update { it.copy(removed = null) }
    }

    /** Moves the song at [index] by [steps] rows; false when it cannot move that way. */
    fun move(index: Int, steps: Int): Boolean {
        val editor = editor ?: return false
        val ids = state.value.entryIds ?: return false
        val target = (index + steps).coerceIn(0, ids.lastIndex)
        if (index !in ids.indices || target == index) return false
        val reordered = ids.toMutableList().apply { add(target, removeAt(index)) }
        // Shown at once so a drag in progress follows the row; the playlist's own update agrees.
        mutableState.update { state ->
            val tracks = state.tracks.toMutableList().apply { add(target, removeAt(index)) }
            state.copy(entryIds = reordered, tracks = tracks)
        }
        editor.reorder(reordered)
        return true
    }

    /** Adds the queue's songs to this Kainos playlist; how many went in. */
    fun addCurrentQueue(): Int = editor?.addCurrentQueue?.invoke() ?: 0

    /** Fetches one page; false when it failed. */
    private suspend fun fetchNext(paged: CollectionSource.Paged): Boolean = pages.withLock {
        val current = mutableState.value
        val token = when (val cursor = current.cursor) {
            PlaylistCursor.First -> null
            is PlaylistCursor.After -> cursor.token
            PlaylistCursor.End -> return@withLock true
        }
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val page = withContext(dispatcher) { paged.page(token) }
            mutableState.update {
                it.copy(
                    header = page.header ?: it.header,
                    tracks = it.tracks + page.tracks,
                    albums = page.albums.ifEmpty { it.albums },
                    total = page.total ?: it.total,
                    cursor = page.next?.let(PlaylistCursor::After) ?: PlaylistCursor.End,
                    notice = page.notice ?: it.notice,
                    loading = false,
                )
            }
            true
        } catch (cancelled: CancellationException) {
            mutableState.update { it.copy(loading = false) }
            throw cancelled
        } catch (failure: Exception) {
            mutableState.update {
                it.copy(loading = false, error = failure.message ?: "${current.header.sourceLabel} did not load.")
            }
            false
        }
    }
}
