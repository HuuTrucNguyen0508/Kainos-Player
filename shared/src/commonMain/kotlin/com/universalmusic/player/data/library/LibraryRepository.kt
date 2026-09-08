package com.universalmusic.player.data.library

import com.universalmusic.player.data.cache.MetadataArtworkCache
import com.universalmusic.player.data.cache.putPreservingOnFailure
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LibraryRepository(
    private val scope: CoroutineScope? = null,
    private val store: UserLibraryStore? = null,
    private val metadataCache: MetadataArtworkCache? = null,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _saved = MutableStateFlow<List<Track>>(emptyList())
    val savedTracks: StateFlow<List<Track>> = _saved.asStateFlow()

    private val _recent = MutableStateFlow<List<Track>>(emptyList())
    val recentlyPlayed: StateFlow<List<Track>> = _recent.asStateFlow()

    private var spotifyAccountId: String? = null
    private val persistMutex = Mutex()
    private var persistJob: Job? = null

    fun isFavorite(canonicalId: String): Boolean = canonicalId in _favorites.value

    fun toggleFavorite(track: Track): Boolean {
        val nowFavorite = track.canonicalId !in _favorites.value
        _favorites.update { current ->
            if (nowFavorite) current + track.canonicalId else current - track.canonicalId
        }
        if (nowFavorite) {
            remember(track)
        } else {
            schedulePersist()
        }
        return nowFavorite
    }

    fun remember(track: Track) {
        upsertSavedAndMaybeCache(track, updateRecents = false)
    }

    fun recordPlay(track: Track) {
        upsertSavedAndMaybeCache(track, updateRecents = true)
    }

    private fun upsertSavedAndMaybeCache(track: Track, updateRecents: Boolean) {
        val sanitized = track.withPersistedSourcesOnly()
        val apply: (Track) -> Unit = { enriched ->
            if (updateRecents) {
                _recent.update { current ->
                    listOf(enriched) + current.filterNot { it.canonicalId == enriched.canonicalId }.take(MAX_RECENTS)
                }
            }
            _saved.update { current ->
                listOf(enriched) + current.filterNot { it.canonicalId == enriched.canonicalId }
            }
            schedulePersist()
        }
        val cache = metadataCache
        val repoScope = scope
        if (cache != null && repoScope != null) {
            repoScope.launch {
                apply(enrichFromCache(sanitized))
            }
        } else {
            apply(sanitized)
        }
    }

    /**
     * Load disk snapshot, scope Spotify entries to [activeSpotifyAccountId], hydrate artwork from cache.
     */
    suspend fun load(activeSpotifyAccountId: String?) {
        val rawStore = store ?: return
        val raw = rawStore.read().migrated()
        val scoped = raw.scopedToSpotifyAccount(activeSpotifyAccountId)
        if (scoped != raw) {
            rawStore.write(scoped)
        }
        applySnapshot(scoped)
        metadataCache?.evictExpired(clock())
    }

    fun applySnapshot(snapshot: UserLibrarySnapshot) {
        spotifyAccountId = snapshot.spotifyAccountId
        _favorites.value = snapshot.favoriteIds.toSet()
        _saved.value = snapshot.remembered.map { it.toDomain() }
        _recent.value = snapshot.recents.map { it.toDomain() }
    }

    /** Drop Spotify-scoped app data when the signed-in account changes or disconnects. */
    suspend fun setSpotifyAccountId(accountId: String?) {
        if (accountId == spotifyAccountId) return
        val scoped = toSnapshot().scopedToSpotifyAccount(accountId)
        applySnapshot(scoped)
        store?.write(scoped)
    }

    fun toSnapshot(): UserLibrarySnapshot = UserLibrarySnapshot(
        version = USER_LIBRARY_FORMAT_VERSION,
        spotifyAccountId = spotifyAccountId,
        favoriteIds = _favorites.value.toList().sorted(),
        remembered = _saved.value.map { it.toPersisted(clock()) },
        recents = _recent.value.map { it.toPersisted(clock()) },
    )

    private fun schedulePersist() {
        val store = store ?: return
        val scope = scope ?: return
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistMutex.withLock {
                store.write(toSnapshot())
            }
        }
    }

    private suspend fun enrichFromCache(track: Track): Track {
        val cache = metadataCache ?: return track
        val persisted = track.toPersisted(clock())
        val entry = cache.putPreservingOnFailure(persisted, clock())
        val art = entry.artworkLocalUri ?: entry.track.artworkUrl ?: track.artwork?.url
        return track.copy(
            artwork = art?.let { url -> track.artwork?.copy(url = url) ?: Artwork(url) },
            sources = track.withPersistedSourcesOnly().sources,
        )
    }

    companion object {
        const val MAX_RECENTS = 40
        private const val PERSIST_DEBOUNCE_MS = 250L
    }
}

/**
 * Strip resolved streaming URLs from non-local sources so in-memory + disk state stay identity-only.
 */
fun Track.withPersistedSourcesOnly(): Track = copy(
    sources = sources.map { source ->
        when (source.provider) {
            ProviderId.LOCAL -> source
            else -> source.copy(
                streamUrl = null,
                handle = PlaybackHandle.ProviderPlayback(
                    provider = source.provider,
                    trackId = source.providerTrackId,
                    durationMs = durationMs,
                ),
            )
        }
    },
)
