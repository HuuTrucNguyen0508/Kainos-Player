package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class PlayerSessionConfinementTest {
    @Test
    fun callsFromForeignThreadsRunSeriallyOnTheSessionThread() = runBlocking {
        val executor = Executors.newSingleThreadExecutor { Thread(it, "kainos-session-test") }
        val sessionDispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = ThreadRecordingEngine()
        try {
            val session = PlayerSession(
                engine = engine,
                resolver = DefaultSourceResolver(),
                scope = scope,
                confineTo = sessionDispatcher,
                workContext = Dispatchers.Default,
            )
            session.play((1..20).map { track("T$it", "A", provider = ProviderId.SAMPLE) }, startIndex = 0)

            // Hammer transport from many threads at once, like UI + MPRIS/D-Bus + media buttons.
            val pool = Executors.newFixedThreadPool(8)
            val start = CountDownLatch(1)
            repeat(64) { i ->
                pool.execute {
                    start.await()
                    when (i % 4) {
                        0 -> session.skipToNext()
                        1 -> session.togglePlayPause()
                        2 -> session.skipToPrevious()
                        else -> session.seekTo(1_000)
                    }
                }
            }
            start.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))

            // Drain everything posted to the session thread, then let in-flight starts settle.
            repeat(3) {
                withContext(sessionDispatcher) { }
                Thread.sleep(50)
            }
            withContext(sessionDispatcher) { }

            // Coroutine debug mode suffixes thread names with " @coroutine#N".
            val offThread = engine.syncCallThreads.filterNot { it.startsWith("kainos-session-test") }
            assertTrue(engine.syncCallThreads.isNotEmpty(), "transport calls were recorded")
            assertEquals(emptyList(), offThread, "engine transport must only be driven from the session thread")
            val now = session.nowPlaying.value
            assertEquals(session.queue.queue.value.current?.id, now.queueItemId, "Now Playing matches the queue")
        } finally {
            scope.cancel()
            executor.shutdownNow()
        }
    }
}

private class ThreadRecordingEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    val syncCallThreads = ConcurrentLinkedQueue<String>()
    @Volatile private var generation = 0L

    private fun record() {
        syncCallThreads += Thread.currentThread().name
    }

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        generation = playGeneration
        state.value = EngineState(status = EngineStatus.PLAYING, durationMs = 1_000, playGeneration = playGeneration)
    }

    override fun pause() {
        record()
        state.value = state.value.copy(status = EngineStatus.PAUSED, playGeneration = generation)
    }

    override fun resume() {
        record()
        state.value = state.value.copy(status = EngineStatus.PLAYING, playGeneration = generation)
    }

    override fun seekTo(positionMs: Long) {
        record()
    }

    override fun stop() {
        record()
        state.value = EngineState(playGeneration = generation)
    }

    override fun setVolume(volume: Float) {
        record()
    }
}
