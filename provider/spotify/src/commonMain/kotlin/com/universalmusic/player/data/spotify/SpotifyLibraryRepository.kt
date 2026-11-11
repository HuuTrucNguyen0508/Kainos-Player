package com.universalmusic.player.data.spotify

import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.ProviderRateLimitedException
import com.universalmusic.player.domain.playback.rateLimitCause
import com.universalmusic.player.platform.PlaybackTrace
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The Web API calls the saved library refreshes from. */
interface SpotifyLibraryRemote {
    /** Up to one page of Liked Songs from [offset], newest first. */
    suspend fun likedSongsPage(offset: Int): LikedSongsPage

    suspend fun libraryPlaylistHeads(): List<SpotifyPlaylistHead>

    suspend fun playlistSongs(playlistId: String): List<Track>
}

/** One Liked Songs entry as Spotify lists it; [track] is null when Spotify cannot show the song. */
data class LikedSongsEntry(val spotifyTrackId: String?, val addedAt: String?, val track: Track?)

data class LikedSongsPage(val entries: List<LikedSongsEntry>, val total: Int, val hasMore: Boolean)

/** A library playlist without songs; [snapshotId] changes whenever its songs change. */
data class SpotifyPlaylistHead(val playlist: Playlist, val snapshotId: String?)

/** Songs of a library playlist kept on this device; [current] when the playlist has not changed since. */
data class SavedPlaylistSongs(val tracks: List<Track>, val current: Boolean)

/** How each section of one refresh ended. A failed section keeps what was shown before. */
data class SpotifyLibraryRefresh(val liked: Result<Unit>, val playlists: Result<Unit>) {
    val succeeded: Boolean get() = liked.isSuccess && playlists.isSuccess
}

/**
 * The signed-in account's Liked Songs and playlists, shown from [store] at launch and refreshed
 * from [remote] by fetching only what changed:
 *
 * - Liked Songs are paged from the newest until an entry already stored (same song, same
 *   added_at). Spotify's total confirms the result; when it disagrees (a song was unliked further
 *   down), the whole list is read again.
 * - Playlists are listed once per refresh; [syncPlaylistSongs] reads songs only of playlists whose
 *   snapshot_id differs from the one their stored songs were read at.
 *
 * A 429 stops the refresh and every later one until its Retry-After has passed; what is shown stays.
 * Rows belong to one account: refreshing for another account, or [clear], deletes them.
 */
class SpotifyLibraryRepository(
    private val store: SpotifyLibraryStore,
    private val remote: SpotifyLibraryRemote,
    private val clock: () -> Long,
    /** Between two playlists' song reads, so a first full read does not burst the API. */
    private val playlistPauseMs: Long = 250,
    /** Wait after a 429 that gave no Retry-After. */
    private val defaultRateLimitMs: Long = 60_000,
) {
    private val _liked = MutableStateFlow<List<Track>>(emptyList())
    val liked: StateFlow<List<Track>> = _liked.asStateFlow()
    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    /** Serializes state changes and store writes; network calls run outside it. */
    private val writes = Mutex()
    private val refreshes = Mutex()
    private val songReads = Mutex()

    /** Bumped by [clear] and account changes, so a refresh that started before neither saves nor shows. */
    private var generation = 0L
    private var account: String? = null
    private var savedLiked: SavedLikedSongs? = null
    private var savedPlaylists: List<SavedSpotifyPlaylist> = emptyList()
    private var playlistsCheckedAtMs: Long? = null

    /** Song reads that failed this run, per playlist; one that failed twice is not asked again until the next launch. */
    private val failedReads = mutableMapOf<String, Int>()

    @Volatile
    var rateLimitedUntilMs: Long = 0
        private set

    /** Something is shown for [account], from the store or a refresh. */
    val hasLibrary: Boolean get() = savedLiked != null || playlistsCheckedAtMs != null

    /** When Spotify last confirmed the oldest shown section; null when nothing is shown. */
    val confirmedAtMs: Long?
        get() = listOfNotNull(savedLiked?.checkedAtMs, playlistsCheckedAtMs).minOrNull()

    /** Shows [accountId]'s stored library. Returns false when nothing is stored for it. */
    suspend fun hydrate(accountId: String): Boolean = writes.withLock {
        if (account != null && account != accountId) return@withLock false
        hydrateLocked(accountId)
    }

    /**
     * Refreshes Liked Songs and the playlist list for [accountId]. Null [accountId] (Spotify's
     * profile did not load) refreshes only an empty library, in memory: it cannot tell whose
     * library a saved one is.
     */
    suspend fun refresh(accountId: String?): SpotifyLibraryRefresh = refreshes.withLock {
        val gen = writes.withLock {
            if (accountId == null) {
                if (account != null || hasLibrary) return@withLock null
            } else if (account != accountId) {
                if (account != null || store.storedAccount().let { it != null && it != accountId }) {
                    resetLocked()
                    store.clear()
                }
                account = accountId
                hydrateLocked(accountId)
            }
            generation
        } ?: return@withLock IllegalStateException("Spotify's profile did not load").let {
            SpotifyLibraryRefresh(Result.failure(it), Result.failure(it))
        }
        waitingOutRateLimit()?.let { return@withLock SpotifyLibraryRefresh(Result.failure(it), Result.failure(it)) }
        val liked = section("Liked Songs") {
            val next = readLiked(writes.withLock { savedLiked })
            commit(gen) {
                savedLiked = next
                accountId?.let { store.saveLiked(it, next) }
            }
        }
        val playlists = if (liked.exceptionOrNull()?.rateLimitCause() != null) {
            Result.failure(liked.exceptionOrNull()!!)
        } else section("playlists") {
            val heads = remote.libraryPlaylistHeads()
            val now = clock()
            commit(gen) {
                val previous = savedPlaylists.associateBy { it.id }
                val next = heads.distinctBy { it.playlist.source.providerEntityId }.map { head ->
                    val id = head.playlist.source.providerEntityId
                    SavedSpotifyPlaylist(head.playlist.copy(tracks = emptyList()), head.snapshotId, previous[id]?.tracksSnapshotId)
                }
                savedPlaylists = next
                playlistsCheckedAtMs = now
                accountId?.let { store.savePlaylists(it, next, now) }
            }
        }
        SpotifyLibraryRefresh(liked, playlists)
    }

    /**
     * Reads and stores the songs of each library playlist that changed since its songs were stored,
     * one at a time. A playlist Spotify refuses (its own editorial lists answer 404) is set aside for
     * this run and the pass goes on; any other failure stops it (a 429 also starts the wait), so an
     * offline pass costs one call. Runs once at a time; a second call while one runs returns.
     */
    suspend fun syncPlaylistSongs() {
        if (!songReads.tryLock()) return
        try {
            while (true) {
                if (waitingOutRateLimit() != null) return
                val (gen, owner, target) = writes.withLock {
                    Triple(generation, account, savedPlaylists.firstOrNull { !it.tracksCurrent && (failedReads[it.id] ?: 0) < MAX_FAILED_READS })
                }
                if (owner == null || target == null) return
                val songs = try {
                    remote.playlistSongs(target.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    val limit = failure.rateLimitCause()
                    val refused = limit == null && failure.refusesOnlyThisPlaylist()
                    writes.withLock {
                        when {
                            limit != null -> noteRateLimit(limit)
                            refused -> failedReads[target.id] = MAX_FAILED_READS
                            else -> failedReads[target.id] = (failedReads[target.id] ?: 0) + 1
                        }
                    }
                    PlaybackTrace.log(TRACE_TAG, "playlist ${target.id} songs not read: ${failure.message ?: failure}")
                    if (!refused) return
                    delay(playlistPauseMs)
                    continue
                }
                // Spotify answers its own editorial playlists with no songs; those stay unread.
                if (songs.isEmpty() && (target.playlist.trackCount ?: 0) > 0) {
                    writes.withLock { failedReads[target.id] = MAX_FAILED_READS }
                    continue
                }
                saveSongs(gen, owner, target.id, target.snapshotId, songs)
                delay(playlistPauseMs)
            }
        } finally {
            songReads.unlock()
        }
    }

    /** The stored songs of library playlist [playlistId], or null when none are stored. */
    suspend fun savedPlaylistSongs(playlistId: String): SavedPlaylistSongs? {
        val (owner, saved) = writes.withLock { account to savedPlaylists.firstOrNull { it.id == playlistId } }
        if (owner == null || saved?.tracksSnapshotId == null) return null
        val tracks = store.playlistTracks(owner, playlistId) ?: return null
        return SavedPlaylistSongs(tracks, saved.tracksCurrent)
    }

    /** Keeps [tracks], just read in full, as the songs of library playlist [playlistId]. */
    suspend fun rememberPlaylistSongs(playlistId: String, tracks: List<Track>) {
        if (tracks.isEmpty()) return
        val (gen, owner, saved) = writes.withLock {
            Triple(generation, account, savedPlaylists.firstOrNull { it.id == playlistId })
        }
        if (owner == null || saved == null) return
        saveSongs(gen, owner, playlistId, saved.snapshotId, tracks)
    }

    private suspend fun hydrateLocked(accountId: String): Boolean {
        if (hasLibrary) return true
        val saved = store.load(accountId) ?: return false
        account = accountId
        savedLiked = saved.liked.takeIf { it.checkedAtMs != null }
        savedPlaylists = saved.playlists
        playlistsCheckedAtMs = saved.playlistsCheckedAtMs
        publish()
        return true
    }

    /** Forgets the library here and on disk (disconnect or client change). */
    suspend fun clear() = writes.withLock {
        resetLocked()
        account = null
        store.clear()
    }

    private suspend fun saveSongs(gen: Long, owner: String, playlistId: String, snapshotId: String?, tracks: List<Track>) {
        commit(gen) {
            store.savePlaylistTracks(owner, playlistId, snapshotId, tracks, clock())
            savedPlaylists = savedPlaylists.map {
                if (it.id == playlistId && it.snapshotId == snapshotId) it.copy(tracksSnapshotId = snapshotId.orEmpty()) else it
            }
        }
    }

    private suspend fun readLiked(known: SavedLikedSongs?): SavedLikedSongs {
        val positions = known?.tracks?.withIndex()?.associate { (index, entry) ->
            likedKey(entry.spotifyTrackId, entry.addedAt) to index
        }.orEmpty()
        val fresh = mutableListOf<SavedLikedTrack>()
        var freshUnshowable = 0
        // Unshowable entries read since the last shown one.
        var unshowableRun = 0
        var anchor: Int? = null
        var total: Int
        var offset = 0
        pages@ while (true) {
            val page = remote.likedSongsPage(offset)
            total = page.total
            for (entry in page.entries) {
                val id = entry.spotifyTrackId
                val known = id?.let { positions[likedKey(it, entry.addedAt)] }
                if (known != null) {
                    anchor = known
                    break@pages
                }
                val track = entry.track
                if (id != null && track != null) {
                    fresh += SavedLikedTrack(id, entry.addedAt, track, unshowableRun)
                    unshowableRun = 0
                } else {
                    freshUnshowable++
                    unshowableRun++
                }
            }
            if (!page.hasMore) break
            offset += page.entries.size
        }
        val now = clock()
        if (anchor == null || known == null) return SavedLikedSongs(fresh, total, freshUnshowable, now)
        // Unshowable entries that were above the anchor are gone or among those just read; the
        // rest are still below it.
        val unshowableAbove = known.tracks.take(anchor + 1).sumOf { it.unshowableBefore }
        val unshowable = freshUnshowable + known.unmapped - unshowableAbove
        val below = listOf(known.tracks[anchor].copy(unshowableBefore = unshowableRun)) + known.tracks.drop(anchor + 1)
        val merged = withoutRelikedCopies(fresh + below)
        // An unlike below the anchor shows only in Spotify's total.
        if (unshowable >= 0 && merged.size + unshowable == total) return SavedLikedSongs(merged, total, unshowable, now)
        PlaybackTrace.log(TRACE_TAG, "Liked Songs count changed below the newest songs; reading all")
        return readLiked(null)
    }

    /**
     * Drops the older entry of a song liked again (Spotify lists each song once); the unshowable
     * entries that sat above the dropped one now sit above the next entry.
     */
    private fun withoutRelikedCopies(entries: List<SavedLikedTrack>): List<SavedLikedTrack> {
        val seen = HashSet<String>()
        var carried = 0
        return entries.mapNotNull { entry ->
            if (!seen.add(entry.spotifyTrackId)) {
                carried += entry.unshowableBefore
                return@mapNotNull null
            }
            val kept = if (carried == 0) entry else entry.copy(unshowableBefore = entry.unshowableBefore + carried)
            carried = 0
            kept
        }
    }

    private suspend fun section(name: String, block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        failure.rateLimitCause()?.let { limit -> writes.withLock { noteRateLimit(limit) } }
        PlaybackTrace.log(TRACE_TAG, "$name refresh failed: ${failure.message ?: failure}")
        Result.failure(failure)
    }

    /** Runs [block] under [writes] unless the library was cleared or switched since [gen]. */
    private suspend fun commit(gen: Long, block: suspend () -> Unit) = writes.withLock {
        if (gen != generation) return@withLock
        block()
        publish()
    }

    private fun waitingOutRateLimit(): ProviderRateLimitedException? {
        val remaining = rateLimitedUntilMs - clock()
        if (remaining <= 0) return null
        return ProviderRateLimitedException("Waiting out Spotify's rate limit", remaining)
    }

    private fun noteRateLimit(limit: ProviderRateLimitedException) {
        rateLimitedUntilMs = maxOf(rateLimitedUntilMs, clock() + (limit.retryAfterMs ?: defaultRateLimitMs))
    }

    private fun resetLocked() {
        generation++
        savedLiked = null
        savedPlaylists = emptyList()
        playlistsCheckedAtMs = null
        failedReads.clear()
        publish()
    }

    private fun publish() {
        _liked.value = savedLiked?.tracks?.map { it.track }.orEmpty()
        _playlists.value = savedPlaylists.map { it.playlist }
    }

    private fun likedKey(trackId: String, addedAt: String?) = "$trackId@${addedAt.orEmpty()}"

    /** Spotify answered for this playlist alone: a client error other than sign-in or rate limit. */
    private fun Exception.refusesOnlyThisPlaylist(): Boolean {
        val status = (this as? SpotifyHttpException)?.status ?: return false
        return status in 400..499 && status != 401 && status != 429
    }

    private companion object {
        const val TRACE_TAG = "SpotifyLibrary"
        const val MAX_FAILED_READS = 2
    }
}

/** "saved 12 min ago", for a library shown from this device while Spotify could not confirm it. */
fun spotifyLibrarySavedAgo(ageMs: Long): String {
    val minutes = ageMs.coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "saved just now"
        minutes < 60 -> "saved $minutes min ago"
        minutes < 24 * 60 -> "saved ${minutes / 60} h ago"
        minutes < 48 * 60 -> "saved yesterday"
        else -> "saved ${minutes / (24 * 60)} days ago"
    }
}
