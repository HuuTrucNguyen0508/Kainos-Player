package com.universalmusic.player.data.db

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import com.universalmusic.player.data.session.ListeningCommit
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.SessionSnapshotStore
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow

internal class PrivateSnapshotWriter(storage: KainosStorage, private val writerId: String = UUID.randomUUID().toString()) : SessionSnapshotStore {
    private val store = DbListeningStore(storage)
    private var sequence = 0L

    override suspend fun read(): SessionSnapshot = store.readRestore().snapshot

    override suspend fun write(snapshot: SessionSnapshot) =
        store.commit(ListeningCommit(writerId, ++sequence, snapshot, PublicationOrigin.LOCAL, local = null, committedAtMs = 0))
}

internal class TracingDriver(private val inner: SqlDriver) : SqlDriver by inner {
    val statements = mutableListOf<String>()
    val threads: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private var changesAtStart = 0L
    var rowsChanged = 0L
        private set

    override fun newTransaction(): QueryResult<Transacter.Transaction> {
        val transaction = inner.newTransaction()
        changesAtStart = inner.totalChanges()
        return transaction
    }

    fun reset() {
        statements.clear()
        rowsChanged = 0
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        val result = inner.execute(identifier, sql, parameters, binders)
        rowsChanged = inner.totalChanges() - changesAtStart
        statements += sql.substringBefore('(').trim()
        threads += Thread.currentThread().name.substringBefore(" @")
        return result
    }
}

internal fun safItem(index: Int) = QueueItem("queue-$index", Track(
    canonicalId = "local:$index",
    title = "SAF $index",
    artists = emptyList(),
    durationMs = null,
    sources = listOf(PlaybackSource(
        provider = ProviderId.LOCAL,
        isPlayable = true,
        providerTrackId = "$index",
        handle = PlaybackHandle.Url("content://test/tree/music/document/$index"),
    )),
))

internal class DurationEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        state.value = EngineState(EngineStatus.PLAYING, durationMs = 180_000, playGeneration = playGeneration)
    }
    override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
    override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun stop() { state.value = state.value.copy(status = EngineStatus.IDLE, durationMs = null) }
    override fun setVolume(volume: Float) = Unit
}
