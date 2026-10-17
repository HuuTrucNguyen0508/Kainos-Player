package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.session.SessionSnapshotStore
import com.universalmusic.player.data.session.toSessionSnapshot
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PlayerSession
import com.universalmusic.player.domain.playback.SessionPersistenceController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class SessionControllerWriteTest {
    @Test
    fun learnedDurationsStayInQueueAndSkipsOnlyWriteStateAfterLearning() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KainosDatabase.Schema.create(driver)
        try {
            val store = DbSessionSnapshotStore(KainosStorage(openDriver = { driver }, clock = { 0 }))
            val changes = mutableListOf<Long>()
            val measured = object : SessionSnapshotStore by store {
                override suspend fun write(snapshot: com.universalmusic.player.data.session.SessionSnapshot) {
                    val before = driver.totalChanges()
                    store.write(snapshot)
                    changes += driver.totalChanges() - before
                }
            }
            val engine = DurationEngine()
            val player = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
            val controller = SessionPersistenceController(player, measured, backgroundScope, clock = { 10 })
            player.restorePaused(PlaybackQueue(
                items = listOf(safItem(0), safItem(1)),
            ).toSessionSnapshot(0, 0))
            controller.flush()
            player.playQueueIndex(0)
            runCurrent()
            controller.flush()
            player.skipToNext()
            runCurrent()
            controller.flush()
            assertEquals(listOf(180_000L, 180_000L), store.read().items.map { it.track.durationMs })
            changes.clear()
            repeat(4) {
                player.playQueueIndex(it % 2)
                runCurrent()
                controller.flush()
            }
            assertEquals(listOf(1L, 1L, 1L, 1L), changes)

            val restored = PlayerSession(DurationEngine(), DefaultSourceResolver(), backgroundScope)
            SessionPersistenceController(restored, store, backgroundScope).restore()
            assertEquals(listOf(180_000L, 180_000L), restored.queue.queue.value.items.map { it.track.durationMs })
            assertFalse(restored.nowPlaying.value.isPlaying)
        } finally {
            driver.close()
        }
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

