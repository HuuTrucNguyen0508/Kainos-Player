package com.universalmusic.player.data.library

import kotlinx.coroutines.CompletableDeferred
import com.universalmusic.player.data.cache.MetadataArtworkCache
import com.universalmusic.player.data.cache.putPreservingOnFailure
import com.universalmusic.player.data.cache.withoutHeartedAudioCache
import com.universalmusic.player.data.sync.HeartAction
import com.universalmusic.player.data.sync.HeartOp
import com.universalmusic.player.data.sync.HeartsSyncDocument
import com.universalmusic.player.data.sync.LOCALFILE_HEART_PREFIX
import com.universalmusic.player.data.sync.compacted
import com.universalmusic.player.data.sync.favoriteIdsFromOps
import com.universalmusic.player.data.sync.forHeartsSync
import com.universalmusic.player.data.sync.isLocalFileHeartCanonicalId
import com.universalmusic.player.data.sync.isPortableHeartCanonicalId
import com.universalmusic.player.data.sync.isProviderHeartCanonicalId
import com.universalmusic.player.data.sync.isSpotifyHeartCanonicalId
import com.universalmusic.player.data.sync.mergeFavoriteMetadata
import com.universalmusic.player.data.sync.mergeHeartOps
import com.universalmusic.player.data.sync.normalizedLocalFileHeartBasename
import com.universalmusic.player.data.sync.portableFavoriteMetadata
import com.universalmusic.player.data.sync.toLocalFileHeartExport
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
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
    private val onFavoriteChanged: ((Track, Boolean) -> Unit)? = null,
    private val deviceIdProvider: () -> String = { "local" },
) {
    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favoriteIds: StateFlow<Set<String>> = _favorites.asStateFlow()

    private val _saved = MutableStateFlow<List<Track>>(emptyList())
    val savedTracks: StateFlow<List<Track>> = _saved.asStateFlow()

    private val _recent = MutableStateFlow<List<Track>>(emptyList())
    val recentlyPlayed: StateFlow<List<Track>> = _recent.asStateFlow()

    private val _homePins = MutableStateFlow<List<PersistedHomePin>>(emptyList())
    val homePins: StateFlow<List<PersistedHomePin>> = _homePins.asStateFlow()

    private var spotifyAccountId: String? = null
    private var heartOps: List<HeartOp> = emptyList()
    private val persistMutex = Mutex()
    private var persistJob: Job? = null

    fun isFavorite(canonicalId: String): Boolean = canonicalId in _favorites.value

    fun isPinned(kind: HomePinKind, targetId: String): Boolean =
        _homePins.value.any { it.kind == kind && it.targetId == targetId }

    fun pinOf(kind: HomePinKind, targetId: String): PersistedHomePin? =
        _homePins.value.firstOrNull { it.kind == kind && it.targetId == targetId }

    /**
     * Add or refresh a Home pin. Does not delete or mutate the underlying playlist/album/folder.
     * Duplicate [kind]+[targetId] updates metadata and keeps position.
     */
    fun pinHome(
        kind: HomePinKind,
        targetId: String,
        title: String,
        subtitle: String? = null,
        artworkUrl: String? = null,
        providerEntityId: String? = null,
        provider: String? = null,
    ): PersistedHomePin? {
        val trimmedTarget = targetId.trim()
        if (trimmedTarget.isEmpty()) return null
        val existing = pinOf(kind, trimmedTarget)
        val pin = PersistedHomePin(
            id = existing?.id ?: newHomePinId(),
            kind = kind,
            targetId = trimmedTarget,
            title = title.trim().ifBlank { existing?.title.orEmpty() }.ifBlank { trimmedTarget },
            subtitle = subtitle ?: existing?.subtitle,
            artworkUrl = artworkUrl ?: existing?.artworkUrl,
            providerEntityId = providerEntityId ?: existing?.providerEntityId,
            provider = provider ?: existing?.provider,
        )
        if (!pin.isValidShape()) return null
        _homePins.update { current ->
            if (existing != null) {
                current.map { if (it.id == existing.id) pin else it }
            } else {
                current + pin
            }.migratedHomePins()
        }
        schedulePersist()
        return pin
    }

    /** Remove the pin only; the playlist/album/folder remains. */
    fun unpinHome(pinId: String): Boolean {
        val before = _homePins.value
        val after = before.filterNot { it.id == pinId }
        if (after.size == before.size) return false
        _homePins.value = after
        schedulePersist()
        return true
    }

    fun unpinHome(kind: HomePinKind, targetId: String): Boolean {
        val pin = pinOf(kind, targetId) ?: return false
        return unpinHome(pin.id)
    }

    fun reorderHomePins(orderedPinIds: List<String>): Boolean {
        val current = _homePins.value
        if (current.isEmpty()) return false
        val byId = current.associateBy { it.id }
        val reordered = orderedPinIds.mapNotNull { byId[it] }
        val missing = current.filter { it.id !in orderedPinIds }
        if (reordered.size + missing.size != current.size) return false
        _homePins.value = (reordered + missing).migratedHomePins()
        schedulePersist()
        return true
    }

    fun moveHomePin(pinId: String, delta: Int): Boolean {
        val current = _homePins.value.toMutableList()
        val index = current.indexOfFirst { it.id == pinId }
        if (index < 0) return false
        val target = (index + delta).coerceIn(0, current.lastIndex)
        if (target == index) return false
        val item = current.removeAt(index)
        current.add(target, item)
        _homePins.value = current
        schedulePersist()
        return true
    }

    fun toggleFavorite(track: Track): Boolean {
        val nowFavorite = track.canonicalId !in _favorites.value
        _favorites.update { current ->
            if (nowFavorite) current + track.canonicalId else current - track.canonicalId
        }
        recordHeartOp(track.canonicalId, if (nowFavorite) HeartAction.FAVORITE else HeartAction.UNFAVORITE)
        if (nowFavorite) {
            remember(track)
        } else {
            schedulePersist()
        }
        onFavoriteChanged?.invoke(track, nowFavorite)
        return nowFavorite
    }

    /** Attach or replace a LOCAL hearted-cache source on a remembered track. */
    fun attachHeartedCacheSource(canonicalId: String, source: PlaybackSource) {
        fun merge(track: Track): Track {
            val withoutOld = track.withoutHeartedAudioCache()
            return withoutOld.copy(sources = listOf(source) + withoutOld.sources).withPersistedSourcesOnly()
        }
        _saved.update { current ->
            current.map { if (it.canonicalId == canonicalId) merge(it) else it }
        }
        _recent.update { current ->
            current.map { if (it.canonicalId == canonicalId) merge(it) else it }
        }
        schedulePersist()
    }

    fun stripHeartedCacheSource(canonicalId: String) {
        fun strip(track: Track): Track = track.withoutHeartedAudioCache().withPersistedSourcesOnly()
        _saved.update { current ->
            current.map { if (it.canonicalId == canonicalId) strip(it) else it }
        }
        _recent.update { current ->
            current.map { if (it.canonicalId == canonicalId) strip(it) else it }
        }
        schedulePersist()
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
     * Completes once [load] has read the store. Until then nothing is written, so a sync merge
     * or tap that races app startup cannot save a half-empty library over the real file.
     */
    private val loaded = CompletableDeferred<Unit>()

    suspend fun awaitLoaded() = loaded.await()

    init {
        // Nothing on disk to protect (tests, previews): treat as loaded.
        if (store == null) loaded.complete(Unit)
    }

    /**
     * Load disk snapshot, scope Spotify entries to [activeSpotifyAccountId], hydrate artwork from cache.
     */
    suspend fun load(activeSpotifyAccountId: String?) {
        try {
            val rawStore = store ?: return
            val raw = rawStore.read().migrated(deviceIdProvider())
            val scoped = raw.scopedToSpotifyAccount(activeSpotifyAccountId)
            if (scoped != raw) {
                rawStore.write(scoped)
            }
            applySnapshot(scoped)
            metadataCache?.evictExpired(clock())
        } finally {
            loaded.complete(Unit)
        }
    }

    fun applySnapshot(snapshot: UserLibrarySnapshot) {
        val migrated = snapshot.migrated(deviceIdProvider())
        spotifyAccountId = migrated.spotifyAccountId
        heartOps = migrated.heartOps.compacted()
        _favorites.value = migrated.favoriteIds.toSet()
        _saved.value = migrated.remembered.map { it.toDomain() }
        _recent.value = migrated.recents.map { it.toDomain() }
        _homePins.value = migrated.homePins.migratedHomePins()
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
        heartOps = heartOps.compacted(),
        remembered = _saved.value.map { it.toPersisted(clock()) },
        recents = _recent.value.map { it.toPersisted(clock()) },
        homePins = _homePins.value.migratedHomePins(),
    )

    fun exportHeartsSyncDocument(): HeartsSyncDocument {
        val favorites = _favorites.value
        val deviceId = deviceIdProvider()
        val now = clock()
        // Home-LAN hearts: YouTube + portable local files only. Spotify is already
        // available on both devices via the same account / Web API — not mirrored here.
        val youtubeMeta = _saved.value
            .filter { it.canonicalId in favorites && it.canonicalId.startsWith("yt:") }
            .mapNotNull { it.toPersisted(0).forHeartsSync() }
        val youtubeOps = heartOps.compacted().filter { it.canonicalId.startsWith("yt:") }
        val localExports = _saved.value
            .filter { it.canonicalId in favorites && it.canonicalId.startsWith("local:") }
            .mapNotNull { track ->
                val revision = heartOps
                    .filter { it.canonicalId == track.canonicalId }
                    .maxOfOrNull { it.revision }
                    ?: now
                track.toPersisted(0).toLocalFileHeartExport(deviceId, revision)
            }
        val pendingLocalFileMeta = _saved.value
            .filter { it.canonicalId in favorites && isLocalFileHeartCanonicalId(it.canonicalId) }
            .mapNotNull { it.toPersisted(0).forHeartsSync() }
        val pendingLocalFileOps = heartOps.compacted().filter { isLocalFileHeartCanonicalId(it.canonicalId) }
        val localfileOps = localExports.map { it.first } + pendingLocalFileOps
        val localfileMeta = localExports.map { it.second } + pendingLocalFileMeta
        val opsById = LinkedHashMap<String, HeartOp>()
        for (op in youtubeOps + localfileOps) {
            val existing = opsById[op.canonicalId]
            if (existing == null || op.revision > existing.revision ||
                (op.revision == existing.revision && op.deviceId > existing.deviceId)
            ) {
                opsById[op.canonicalId] = op
            }
        }
        return HeartsSyncDocument(
            deviceId = deviceId,
            spotifyAccountId = spotifyAccountId,
            ops = opsById.values.sortedWith(compareBy({ it.canonicalId }, { it.revision }, { it.deviceId })),
            favoritesMetadata = (youtubeMeta + localfileMeta).portableFavoriteMetadata(),
        )
    }

    /**
     * Merge a remote hearts document into local state, persist under [persistMutex], and
     * fire [onFavoriteChanged] for favorite deltas so hearted-audio cache stays aligned.
     *
     * Portable [localfile:] hearts are kept until [rematchPortableLocalFileHearts] maps them
     * onto device `local:` tracks after the file is present.
     */
    suspend fun mergeAndPersistSyncState(remote: HeartsSyncDocument): HeartsSyncDocument {
        persistJob?.cancel()
        persistJob = null
        val deltas = mutableListOf<Pair<Track, Boolean>>()
        persistMutex.withLock {
            val before = _favorites.value
            val mergedOps = mergeHeartOps(
                localOps = heartOps,
                remoteOps = remote.ops.filterNot { isSpotifyHeartCanonicalId(it.canonicalId) },
                localSpotifyAccountId = spotifyAccountId,
                remoteSpotifyAccountId = remote.spotifyAccountId,
            ).compacted()
            // Preserve device-local favorite ids; portable wire ids come from ops.
            val portableFavorites = mergedOps.favoriteIdsFromOps()
            val localOnlyFavorites = before.filter {
                it.startsWith("local:") && !isPortableHeartCanonicalId(it)
            }
            val after = portableFavorites + localOnlyFavorites

            val localRemembered = _saved.value.map { it.toPersisted(clock()) }
            val mergedMeta = mergeFavoriteMetadata(
                local = localRemembered,
                remote = remote.favoritesMetadata,
                favoriteIds = after,
            )
            val rememberedById = LinkedHashMap<String, Track>()
            for (track in _saved.value) {
                rememberedById[track.canonicalId] = track
            }
            for (persisted in mergedMeta) {
                val existing = rememberedById[persisted.canonicalId]
                rememberedById[persisted.canonicalId] = if (existing != null) {
                    // Keep local cache sources; overlay portable metadata fields.
                    existing.copy(
                        title = persisted.title.ifBlank { existing.title },
                        artists = if (persisted.artists.isNotEmpty()) {
                            persisted.toDomain().artists
                        } else {
                            existing.artists
                        },
                        album = persisted.toDomain().album ?: existing.album,
                        durationMs = persisted.durationMs ?: existing.durationMs,
                        artwork = persisted.artworkUrl?.let(::Artwork) ?: existing.artwork,
                        explicit = persisted.explicit || existing.explicit,
                        isrc = persisted.isrc ?: existing.isrc,
                        sources = existing.sources,
                    ).withPersistedSourcesOnly()
                } else {
                    persisted.toDomain().withPersistedSourcesOnly()
                }
            }
            // Keep non-favorite remembered rows (history); ensure every favorite has metadata when known.
            val nextSaved = buildList {
                addAll(rememberedById.values)
                val have = rememberedById.keys
                for (id in after) {
                    if (id in have) continue
                    val persisted = mergedMeta.firstOrNull { it.canonicalId == id } ?: continue
                    add(persisted.toDomain().withPersistedSourcesOnly())
                }
            }.distinctBy { it.canonicalId }

            heartOps = mergedOps
            _favorites.value = after
            _saved.value = nextSaved

            for (id in after - before) {
                val track = nextSaved.firstOrNull { it.canonicalId == id } ?: continue
                deltas += track to true
            }
            for (id in before - after) {
                val track = rememberedById[id]
                    ?: _recent.value.firstOrNull { it.canonicalId == id }
                    ?: continue
                deltas += track to false
            }

            store?.write(toSnapshot())
        }
        for ((track, nowFavorite) in deltas) {
            onFavoriteChanged?.invoke(track, nowFavorite)
        }
        return exportHeartsSyncDocument()
    }

    /**
     * When a [localfile:] heart's basename matches a unique library track, heart that
     * `local:` id and drop the portable stub. [uniqueLocalTracksByBasenameLower] must
     * only contain basenames with exactly one library match.
     */
    suspend fun rematchPortableLocalFileHearts(
        uniqueLocalTracksByBasenameLower: Map<String, Track>,
    ): Int {
        val deltas = mutableListOf<Pair<Track, Boolean>>()
        var rematched = 0
        persistMutex.withLock {
            val favorites = _favorites.value.toMutableSet()
            val portableIds = favorites.filter { isLocalFileHeartCanonicalId(it) }
            if (portableIds.isEmpty()) return@withLock
            var ops = heartOps
            val saved = _saved.value.toMutableList()
            for (portableId in portableIds) {
                val key = normalizedLocalFileHeartBasename(portableId) ?: continue
                val match = uniqueLocalTracksByBasenameLower[key] ?: continue
                favorites.remove(portableId)
                favorites.add(match.canonicalId)
                val portableOp = ops.filter { it.canonicalId == portableId }.maxByOrNull { it.revision }
                val revision = portableOp?.revision ?: clock()
                ops = (
                    ops.filterNot { it.canonicalId == portableId } + HeartOp(
                        canonicalId = match.canonicalId,
                        action = HeartAction.FAVORITE,
                        revision = revision,
                        deviceId = deviceIdProvider(),
                    )
                    ).compacted()
                if (saved.none { it.canonicalId == match.canonicalId }) {
                    saved += match
                }
                saved.firstOrNull { it.canonicalId == portableId }?.let { deltas += it to false }
                deltas += match to true
                rematched += 1
            }
            heartOps = ops
            _favorites.value = favorites
            _saved.value = saved.distinctBy { it.canonicalId }
            store?.write(toSnapshot())
        }
        for ((track, nowFavorite) in deltas) {
            onFavoriteChanged?.invoke(track, nowFavorite)
        }
        return rematched
    }

    private fun recordHeartOp(canonicalId: String, action: HeartAction) {
        val op = HeartOp(
            canonicalId = canonicalId,
            action = action,
            revision = clock(),
            deviceId = deviceIdProvider(),
            spotifyAccountId = if (canonicalId.startsWith("spotify:")) spotifyAccountId else null,
        )
        heartOps = (heartOps + op).compacted()
    }

    private fun schedulePersist() {
        val store = store ?: return
        val scope = scope ?: return
        if (!loaded.isCompleted) return
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
