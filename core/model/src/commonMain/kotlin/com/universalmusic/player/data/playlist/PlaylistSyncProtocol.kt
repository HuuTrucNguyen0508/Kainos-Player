package com.universalmusic.player.data.playlist

import kotlinx.coroutines.CompletableDeferred
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.data.sync.LocalSyncIdentityIndex
import com.universalmusic.player.data.sync.localSyncIdentity
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

/** One-release v1 export. Remaps keyed entries to basename ids and removes their keys. */
fun PlaylistsSyncDocument.forV1Sync(): PlaylistsSyncDocument = copy(
    playlists = playlists.map { it.forPlaylistSync(useContentKeys = false) },
)
