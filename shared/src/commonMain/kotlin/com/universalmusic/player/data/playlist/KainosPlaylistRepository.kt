package com.universalmusic.player.data.playlist

import kotlinx.coroutines.CompletableDeferred
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.data.sync.isLocalFileHeartCanonicalId
import com.universalmusic.player.data.sync.normalizedLocalFileHeartBasename
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * App-owned playlists: create / rename / delete / reorder / add / remove / save queue.
 * Persistence is versioned JSON; never stores stream URLs.
 */
class KainosPlaylistRepository(
    private val scope: CoroutineScope? = null,
    private val store: KainosPlaylistStore? = null,
    private val clock: () -> Long = ::currentTimeMillis,
    private val deviceIdProvider: () -> String = { "local" },
) {
    private val _playlists = MutableStateFlow<List<PersistedKainosPlaylist>>(emptyList())
    val playlists: StateFlow<List<PersistedKainosPlaylist>> = _playlists.asStateFlow()

    private var tombstones: List<PlaylistTombstone> = emptyList()
    private val persistMutex = Mutex()
    private var persistJob: Job? = null

    /**
     * Completes once [load] has read the store. Until then nothing is written, so a sync merge
     * or tap that races app startup cannot save a half-empty playlist set over the real file.
     */
    private val loaded = CompletableDeferred<Unit>()

    suspend fun awaitLoaded() = loaded.await()

    init {
        // Nothing on disk to protect (tests, previews): treat as loaded.
        if (store == null) loaded.complete(Unit)
    }

    suspend fun load() {
        try {
            val rawStore = store ?: return
            val snapshot = rawStore.read().migrated()
            applySnapshot(snapshot)
            rawStore.write(toSnapshot())
        } finally {
            loaded.complete(Unit)
        }
    }

    fun applySnapshot(snapshot: KainosPlaylistsSnapshot) {
        val migrated = snapshot.migrated()
        tombstones = migrated.tombstones
        _playlists.value = migrated.playlists
    }

    fun toSnapshot(): KainosPlaylistsSnapshot = KainosPlaylistsSnapshot(
        version = KAINOS_PLAYLISTS_FORMAT_VERSION,
        playlists = _playlists.value,
        tombstones = tombstones,
    )

    fun get(id: String): PersistedKainosPlaylist? =
        _playlists.value.firstOrNull { it.id == id }

    fun create(title: String, tracks: List<Track> = emptyList()): PersistedKainosPlaylist {
        val now = clock()
        val playlist = PersistedKainosPlaylist(
            id = newKainosPlaylistId(),
            title = title.trim().ifBlank { "Playlist" },
            entries = tracks.map { track ->
                PersistedPlaylistEntry(
                    entryId = newPlaylistEntryId(),
                    track = track.toPlaylistPersisted(now),
                )
            },
            createdAtMs = now,
            updatedAtMs = now,
            revision = now,
            deviceId = deviceIdProvider(),
        )
        _playlists.value = _playlists.value + playlist
        // Clear any stale tombstone for a recycled id (ids are random; defensive).
        tombstones = tombstones.filterNot { it.playlistId == playlist.id }
        schedulePersist()
        return playlist
    }

    /** Save current queue order and provider identities as a new Kainos playlist. */
    fun saveQueueAsPlaylist(title: String, tracks: List<Track>): PersistedKainosPlaylist =
        create(title = title, tracks = tracks)

    fun rename(id: String, title: String): PersistedKainosPlaylist? {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return null
        return mutate(id) { it.copy(title = trimmed) }
    }

    fun delete(id: String): Boolean {
        val existing = get(id) ?: return false
        val now = clock()
        _playlists.value = _playlists.value.filterNot { it.id == id }
        val tomb = PlaylistTombstone(
            playlistId = id,
            revision = maxOf(existing.revision + 1, now),
            deviceId = deviceIdProvider(),
            deletedAtMs = now,
        )
        tombstones = (tombstones.filterNot { it.playlistId == id } + tomb)
        schedulePersist()
        return true
    }

    fun reorderPlaylists(orderedIds: List<String>): Boolean {
        val byId = _playlists.value.associateBy { it.id }
        if (orderedIds.toSet() != byId.keys) return false
        val now = clock()
        val deviceId = deviceIdProvider()
        _playlists.value = orderedIds.mapNotNull { id ->
            byId[id]?.copy(updatedAtMs = now, revision = now, deviceId = deviceId)
        }
        schedulePersist()
        return true
    }

    fun reorderEntries(playlistId: String, orderedEntryIds: List<String>): PersistedKainosPlaylist? {
        return mutate(playlistId) { playlist ->
            val byId = playlist.entries.associateBy { it.entryId }
            if (orderedEntryIds.toSet() != byId.keys) return@mutate playlist
            playlist.copy(entries = orderedEntryIds.mapNotNull { byId[it] })
        }
    }

    fun addTracks(playlistId: String, tracks: List<Track>, allowDuplicates: Boolean = true): PersistedKainosPlaylist? {
        if (tracks.isEmpty()) return get(playlistId)
        val now = clock()
        return mutate(playlistId) { playlist ->
            val existingIds = playlist.entries.map { it.track.canonicalId }.toSet()
            val toAdd = tracks.filter { allowDuplicates || it.canonicalId !in existingIds }
            playlist.copy(
                entries = playlist.entries + toAdd.map { track ->
                    PersistedPlaylistEntry(
                        entryId = newPlaylistEntryId(),
                        track = track.toPlaylistPersisted(now),
                    )
                },
            )
        }
    }

    fun removeEntry(playlistId: String, entryId: String): PersistedKainosPlaylist? =
        mutate(playlistId) { playlist ->
            playlist.copy(entries = playlist.entries.filterNot { it.entryId == entryId })
        }

    fun removeTrackByCanonicalId(playlistId: String, canonicalId: String): PersistedKainosPlaylist? =
        mutate(playlistId) { playlist ->
            playlist.copy(entries = playlist.entries.filterNot { it.track.canonicalId == canonicalId })
        }

    /**
     * Domain tracks for playback, preserving playlist order. Missing localfile stubs stay
     * in the list so the user can see them; [PlayerSession] resolver will skip unplayable ones.
     */
    fun tracksForPlayback(playlistId: String): List<Track> =
        get(playlistId)?.entries?.map { it.track.toDomain() }.orEmpty()

    fun exportSyncDocument(): PlaylistsSyncDocument {
        val deviceId = deviceIdProvider()
        return PlaylistsSyncDocument(
            deviceId = deviceId,
            playlists = _playlists.value.map { it.forPlaylistSync() },
            tombstones = tombstones,
        )
    }

    /**
     * Merge remote playlist metadata. Higher revision wins; tombstones with revision >=
     * playlist revision delete. Does not copy vault audio.
     */
    suspend fun mergeAndPersistSyncState(remote: PlaylistsSyncDocument): PlaylistsSyncDocument {
        persistJob?.cancel()
        persistJob = null
        persistMutex.withLock {
            val merged = mergePlaylistSync(
                local = toSnapshot(),
                remote = remote,
            )
            applySnapshot(merged)
            store?.write(toSnapshot())
        }
        return exportSyncDocument()
    }

    /**
     * Remap portable [localfile:] entries onto unique same-basename library tracks.
     * Returns number of rematched entries.
     */
    suspend fun rematchPortableLocalFileEntries(
        uniqueLocalTracksByBasenameLower: Map<String, Track>,
    ): Int {
        if (uniqueLocalTracksByBasenameLower.isEmpty()) return 0
        var rematched = 0
        val now = clock()
        persistMutex.withLock {
            val next = _playlists.value.map { playlist ->
                var changed = false
                val entries = playlist.entries.map { entry ->
                    val id = entry.track.canonicalId
                    if (!isLocalFileHeartCanonicalId(id)) return@map entry
                    val key = normalizedLocalFileHeartBasename(id) ?: return@map entry
                    val match = uniqueLocalTracksByBasenameLower[key] ?: return@map entry
                    changed = true
                    rematched += 1
                    entry.copy(track = match.toPlaylistPersisted(now))
                }
                if (!changed) playlist
                else playlist.copy(
                    entries = entries,
                    updatedAtMs = now,
                    revision = now,
                    deviceId = deviceIdProvider(),
                )
            }
            _playlists.value = next
            store?.write(toSnapshot())
        }
        return rematched
    }

    private fun mutate(
        id: String,
        transform: (PersistedKainosPlaylist) -> PersistedKainosPlaylist,
    ): PersistedKainosPlaylist? {
        val current = get(id) ?: return null
        val now = clock()
        val updated = transform(current).copy(
            updatedAtMs = now,
            revision = now,
            deviceId = deviceIdProvider(),
        )
        _playlists.value = _playlists.value.map { if (it.id == id) updated else it }
        schedulePersist()
        return updated
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

    companion object {
        private const val PERSIST_DEBOUNCE_MS = 250L
    }
}

@Serializable
data class PlaylistsSyncDocument(
    val deviceId: String,
    val playlists: List<PersistedKainosPlaylist> = emptyList(),
    val tombstones: List<PlaylistTombstone> = emptyList(),
)

/**
 * Merge local and remote playlist snapshots for Home sync.
 *
 * Rules:
 * - Per playlist id, higher [PersistedKainosPlaylist.revision] wins (tie: lexicographic deviceId).
 * - A tombstone beats a live playlist when [PlaylistTombstone.beatsPlaylist].
 * - Tombstones themselves merge by highest revision per playlist id.
 * - Entries inside the winning playlist are taken wholesale (last-write-wins playlist body).
 * - Local-only `local:` path entries are not accepted from remote; remote must use portable ids.
 * - Never copies audio blobs.
 */
fun mergePlaylistSync(
    local: KainosPlaylistsSnapshot,
    remote: PlaylistsSyncDocument,
): KainosPlaylistsSnapshot {
    val localMigrated = local.migrated()
    val remotePlaylists = remote.playlists
        .filter { isKainosPlaylistId(it.id) }
        .map { it.forPlaylistSync() }
    val remoteTombs = remote.tombstones.filter { isKainosPlaylistId(it.playlistId) }

    val tombById = LinkedHashMap<String, PlaylistTombstone>()
    for (tomb in localMigrated.tombstones + remoteTombs) {
        val existing = tombById[tomb.playlistId]
        if (existing == null ||
            tomb.revision > existing.revision ||
            (tomb.revision == existing.revision && tomb.deviceId > existing.deviceId)
        ) {
            tombById[tomb.playlistId] = tomb
        }
    }

    val byId = LinkedHashMap<String, PersistedKainosPlaylist>()
    for (playlist in localMigrated.playlists + remotePlaylists) {
        val existing = byId[playlist.id]
        if (existing == null || playlist.beats(existing)) {
            byId[playlist.id] = playlist
        }
    }

    val live = byId.values.filter { playlist ->
        val tomb = tombById[playlist.id] ?: return@filter true
        !tomb.beatsPlaylist(playlist)
    }

    // Drop tombstones superseded by a newer live playlist.
    val tombs = tombById.values.filter { tomb ->
        val livePlaylist = live.firstOrNull { it.id == tomb.playlistId }
        livePlaylist == null || tomb.beatsPlaylist(livePlaylist)
    }

    return KainosPlaylistsSnapshot(
        version = KAINOS_PLAYLISTS_FORMAT_VERSION,
        playlists = live.toList(),
        tombstones = tombs.sortedWith(compareBy({ it.playlistId }, { it.revision })),
    ).migrated()
}
