package com.universalmusic.player.data.loudness

import com.universalmusic.player.data.sync.LoudnessSyncDocument
import com.universalmusic.player.data.sync.exportLoudness
import com.universalmusic.player.data.sync.importLoudness
import com.universalmusic.player.domain.playback.TrackLoudness
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Every stored measurement, held in memory so the player can look one up on any thread without a
 * database read. [record] writes through to [store]; a null store keeps values for this run only.
 */
class LoudnessRepository(private val store: LoudnessStore?) : LoudnessCatalog {
    private val rows = MutableStateFlow<Map<String, StoredLoudness>>(emptyMap())
    private val writes = Mutex()

    /** Reads the stored rows once; rows recorded before this finishes are kept over stored ones. */
    suspend fun load() {
        val stored = store?.readAll().orEmpty()
        rows.update { current -> stored.associateBy(StoredLoudness::location) + current }
    }

    override fun loudnessOf(location: String): TrackLoudness? {
        val row = stored(location) ?: return null
        val track = row.track ?: return null
        return TrackLoudness(track, row.album, row.albumKey)
    }

    override fun stored(location: String): StoredLoudness? = rows.value[loudnessLocationKey(location)]

    override suspend fun record(rows: List<StoredLoudness>) {
        if (rows.isEmpty()) return
        val keyed = rows.map { it.copy(location = loudnessLocationKey(it.location)) }
        writes.withLock {
            store?.upsert(keyed)
            this.rows.update { current -> current + keyed.associateBy(StoredLoudness::location) }
        }
    }

    /** This device's measurements of [subjects], for a Home sync peer. */
    fun export(deviceId: String, subjects: List<LoudnessSubject>): LoudnessSyncDocument =
        exportLoudness(deviceId, ::stored, subjects)

    /** Stores what [remote] measured of this device's [subjects]; returns how many rows changed. */
    suspend fun import(remote: LoudnessSyncDocument, subjects: List<LoudnessSubject>): Int {
        val received = importLoudness(remote, subjects, ::stored)
        record(received)
        return received.size
    }
}
