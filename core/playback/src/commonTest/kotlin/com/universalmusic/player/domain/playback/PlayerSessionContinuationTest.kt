package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.continuation.QueueContinuation
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionContinuationTest {
    @Test
    fun refillsAheadOfTheEndAtTheLowWaterMark() = runTest {
        val (engine, session) = rig()
        val radio = ScriptedContinuation(lowWater = 1, listOf(t("a"), t("b"), t("c")), listOf(t("d")))

        session.play(listOf(t("seed")), continuation = radio)
        runCurrent()
        assertEquals(listOf("seed", "a", "b", "c"), session.titles(), "nothing upcoming: refill as soon as it starts")

        engine.emitEnded()
        runCurrent()
        assertEquals(1, radio.requests.size, "two upcoming is above the mark")

        engine.emitEnded()
        runCurrent()
        assertEquals("b", session.nowPlaying.value.track?.title)
        assertEquals(listOf("seed", "a", "b", "c", "d"), session.titles())
        assertEquals("b", radio.requests.last().current?.track?.title, "the batch is asked for with the live queue")
    }

    @Test
    fun queueEndWaitsForThePendingBatchThenPlaysIt() = runTest {
        val (engine, session) = rig()
        val gate = CompletableDeferred<Unit>()
        val radio = ScriptedContinuation(lowWater = 2, listOf(t("a")), gate = gate)

        session.play(listOf(t("seed")), continuation = radio)
        runCurrent()
        engine.emitEnded()
        runCurrent()
        assertEquals("seed", session.nowPlaying.value.track?.title)

        gate.complete(Unit)
        runCurrent()
        assertEquals("a", session.nowPlaying.value.track?.title)
        assertEquals(EngineStatus.PLAYING, engine.state.value.status)
    }

    @Test
    fun otherPlaybackDropsTheContinuationAndItsLateBatch() = runTest {
        val (engine, session) = rig()
        val gate = CompletableDeferred<Unit>()
        val radio = ScriptedContinuation(lowWater = 2, listOf(t("late")), gate = gate)

        session.play(listOf(t("seed")), continuation = radio)
        runCurrent()
        session.play(listOf(t("x"), t("y")))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("x", "y"), session.titles())

        engine.emitEnded()
        runCurrent()
        engine.emitEnded()
        runCurrent()
        assertEquals(1, radio.requests.size)
        assertFalse(session.nowPlaying.value.isPlaying)
    }

    @Test
    fun clearingTheQueueStopsRefills() = runTest {
        val (_, session) = rig()
        val radio = ScriptedContinuation(lowWater = 2, listOf(t("a")), listOf(t("b")))

        session.play(listOf(t("seed")), continuation = radio)
        runCurrent()
        session.clearQueue()
        runCurrent()
        session.addToQueue(t("manual"))
        session.playQueueIndex(0)
        runCurrent()
        assertEquals(1, radio.requests.size)
        assertEquals(listOf("manual"), session.titles())
    }

    @Test
    fun manualQueueEntriesStayAheadOfTheBatch() = runTest {
        val (_, session) = rig()
        val gate = CompletableDeferred<Unit>()
        val radio = ScriptedContinuation(lowWater = 2, listOf(t("a"), t("b")), gate = gate)

        session.play(listOf(t("seed")), continuation = radio)
        runCurrent()
        session.addToQueue(t("queued"))
        session.playNext(t("next"))
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("seed", "next", "queued", "a", "b"), session.titles())
    }

    @Test
    fun anEmptyBatchFinishesTheContinuation() = runTest {
        val (engine, session) = rig()
        val radio = ScriptedContinuation(lowWater = 2)

        session.play(listOf(t("one"), t("two")), continuation = radio)
        runCurrent()
        engine.emitEnded()
        runCurrent()
        engine.emitEnded()
        runCurrent()
        assertEquals(1, radio.requests.size)
        assertEquals("two", session.nowPlaying.value.track?.title)
        assertFalse(session.nowPlaying.value.isPlaying)
    }

    @Test
    fun repeatAllLoopsInsteadOfRefilling() = runTest {
        val (engine, session) = rig()
        val radio = ScriptedContinuation(lowWater = 2, listOf(t("a")))
        session.cycleRepeat()
        assertEquals(RepeatMode.ALL, session.queueState.value.repeat)

        session.play(listOf(t("one"), t("two")), continuation = radio)
        runCurrent()
        engine.emitEnded()
        runCurrent()
        engine.emitEnded()
        runCurrent()
        assertEquals(0, radio.requests.size)
        assertEquals("one", session.nowPlaying.value.track?.title)
    }

    private fun TestScope.rig(): Pair<ControllableEngine, PlayerSession> {
        val engine = ControllableEngine()
        return engine to PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
    }

    private fun PlayerSession.titles() = queueState.value.items.map { it.track.title }

    private fun t(title: String) = track(title, "Artist", provider = ProviderId.SAMPLE, canonicalId = "sample:$title")
}

private class ScriptedContinuation(
    override val lowWater: Int,
    vararg batches: List<Track>,
    private val gate: CompletableDeferred<Unit>? = null,
) : QueueContinuation {
    val requests = mutableListOf<PlaybackQueue>()
    private val pending = ArrayDeque(batches.toList())

    override suspend fun nextBatch(queue: PlaybackQueue): List<Track> {
        requests += queue
        gate?.await()
        return pending.removeFirstOrNull().orEmpty()
    }
}
