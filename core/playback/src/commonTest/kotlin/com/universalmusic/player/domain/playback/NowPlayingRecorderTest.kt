package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.session.ListeningRestore
import com.universalmusic.player.data.session.LocalListeningExport
import com.universalmusic.player.data.session.LocalListeningPublication
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.PublicationRevision
import com.universalmusic.player.data.session.SessionRevision
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingRecorderTest {
    @Test
    fun eachTransportAndQueueChangeBecomesTheLatestRow() = runTest {
        val rig = rig()
        val session = rig.session
        session.initializePersistence()
        session.play(listOf(yt("a"), yt("b"), yt("c")), startIndex = 0)
        settle(rig)
        val ids = session.queueState.value.items.map { it.id }
        assertEquals(Row(ids[0], "yt:a", 0, T0, true, T0), rig.store.latest.row())

        advanceTimeBy(5_000)
        rig.engine.report(positionMs = 5_000)
        val published = rig.store.publications.size
        settle(rig)
        assertEquals(published, rig.store.publications.size, "position ticks are not listening activity")

        session.pauseTransport()
        settle(rig)
        assertEquals(Row(ids[0], "yt:a", 5_000, T0 + 5_000, false, T0 + 5_000), rig.store.latest.row())

        advanceTimeBy(1_000)
        session.seekTo(42_000)
        settle(rig)
        assertEquals(Row(ids[0], "yt:a", 42_000, T0 + 6_000, false, T0 + 6_000), rig.store.latest.row())

        advanceTimeBy(1_000)
        session.playTransport()
        settle(rig)
        assertEquals(Row(ids[0], "yt:a", 42_000, T0 + 7_000, true, T0 + 7_000), rig.store.latest.row())

        advanceTimeBy(1_000)
        rig.engine.report(EngineStatus.ENDED)
        settle(rig)
        assertEquals(Row(ids[1], "yt:b", 0, T0 + 8_000, true, T0 + 8_000), rig.store.latest.row())

        session.skipToNext()
        settle(rig)
        assertEquals(Row(ids[2], "yt:c", 0, T0 + 8_000, true, T0 + 8_000), rig.store.latest.row())

        advanceTimeBy(1_000)
        rig.engine.report(EngineStatus.ENDED)
        settle(rig)
        assertEquals(Row(ids[2], "yt:c", 0, T0 + 9_000, false, T0 + 9_000), rig.store.latest.row())

        val revisions = rig.store.publications.map { it.revision.value }
        assertEquals(revisions.sorted().distinct(), revisions)
    }

    @Test
    fun sleepTimerStopsRecordTheirFinalPositionAsAutomaticChanges() = runTest {
        val endOfTrack = rig()
        endOfTrack.session.initializePersistence()
        endOfTrack.session.play(listOf(yt("a"), yt("b")), startIndex = 0)
        runCurrent()
        val ids = endOfTrack.session.queueState.value.items.map { it.id }
        SleepTimerController(endOfTrack.session, backgroundScope) { 0L }.startEndOfTrack()
        runCurrent()
        advanceTimeBy(3_000)
        endOfTrack.engine.report(EngineStatus.ENDED, positionMs = 200_000)
        settle(endOfTrack)
        assertEquals(Row(ids[0], "yt:a", 200_000, T0 + 3_000, false, T0 + 3_000), endOfTrack.store.latest.row())

        val timed = rig()
        timed.session.initializePersistence()
        timed.session.play(listOf(yt("a")), startIndex = 0)
        runCurrent()
        val timedId = timed.session.queueState.value.items.single().id
        var monotonic = 0L
        SleepTimerController(timed.session, backgroundScope) { monotonic }.startDuration(5_000)
        runCurrent()
        timed.engine.report(positionMs = 4_000)
        monotonic = 5_000
        advanceTimeBy(1_000)
        settle(timed)
        assertEquals(Row(timedId, "yt:a", 4_000, T0 + 4_000, false, T0 + 4_000), timed.store.latest.row())

        val stopped = timed.session.localRevision
        timed.session.pauseForSleepTimer()
        assertEquals(stopped, timed.session.localRevision, "an automatic pause while paused changes nothing")
        timed.session.pauseTransport()
        assertEquals(SessionRevision(stopped.value + 1), timed.session.localRevision, "a user pause still records intent")
    }

    @Test
    fun pausedSeekAdvancesTheRevisionBeforeTheBlockedWriteLands() = runTest {
        val store = FakeListeningStore(ListeningRestore(snapshot(yt("a"), yt("b"), positionMs = 12_000), PublicationOrigin.LOCAL))
        val rig = rig(store)
        val session = rig.session
        session.initializePersistence()
        store.commitGate = CompletableDeferred()
        val beforeSeek = session.localRevision

        session.seekTo(30_000)
        assertEquals(SessionRevision(beforeSeek.value + 1), session.localRevision)
        assertEquals(30_000, session.nowPlaying.value.positionMs)
        runCurrent()
        assertEquals(1, store.attempts, "the write is in flight and blocked")

        session.playNext(yt("n"))
        assertFalse(session.loadRemotePaused(snapshot(yt("r")), beforeSeek), "a remote load with a stale guard loses")
        assertEquals(listOf("yt:a", "yt:n", "yt:b"), session.queueState.value.items.map { it.track.canonicalId })

        checkNotNull(store.commitGate).complete(Unit)
        settle(rig)
        assertEquals(Row("q-a", "yt:a", 30_000, T0, false, T0), rig.store.latest.row())
        assertEquals(listOf("yt:a", "yt:n", "yt:b"), rig.store.latest.state.queue.entries.map { it.track.identity })
    }

    @Test
    fun blockedProjectionKeepsTheSessionCurrentAndCoalescesTheBurst() = runTest {
        val worker = HeldDispatcher()
        val rig = rig(workerContext = worker)
        val session = rig.session
        session.initializePersistence()
        session.play(listOf(yt("a"), yt("b"), yt("c"), yt("d")), startIndex = 0)
        runCurrent()
        val start = session.localRevision

        repeat(10) { session.playQueueIndex(it % 4) }
        session.moveInQueue(0, 3)
        assertEquals(SessionRevision(start.value + 11), session.localRevision)
        assertEquals(session.queueState.value.current?.id, session.nowPlaying.value.queueItemId)
        session.pauseTransport()
        session.seekTo(77_000)
        assertEquals(SessionRevision(start.value + 13), session.localRevision)
        assertEquals(77_000, session.nowPlaying.value.positionMs)
        assertEquals(0, rig.store.attempts)

        val flushed = async { session.flushPersistence() }
        repeat(5) {
            runCurrent()
            worker.runAll()
        }
        assertEquals(FlushResult.Committed(), flushed.await())
        assertTrue(rig.store.attempts <= 2, "one commit for the burst and one for the flush, got ${rig.store.attempts}")
        val final = session.queueState.value
        assertEquals(final.currentIndex, rig.store.commits.last().snapshot.currentIndex)
        assertEquals(final.current?.id, rig.store.latest.state.current?.itemId)
        assertEquals(final.items.map { it.track.canonicalId }, rig.store.latest.state.queue.entries.map { it.track.identity })
        assertEquals(77_000, rig.store.latest.state.positionMs)
    }

    @Test
    fun failedWriteRetriesTheLatestStateWithoutRollingBackTheSession() = runTest {
        val store = FakeListeningStore().apply {
            failure = IllegalStateException("disk full")
            failuresLeft = 1
        }
        val rig = rig(store)
        val session = rig.session
        session.initializePersistence()
        session.play(listOf(yt("a")), startIndex = 0)
        runCurrent()
        val revision = session.localRevision

        assertEquals(FlushResult.Committed(), session.flushPersistence())
        assertEquals(revision, session.localRevision)
        assertEquals(Row(session.queueState.value.items.single().id, "yt:a", 0, T0, true, T0), store.latest.row())
        assertTrue(store.attempts >= 2)

        store.failuresLeft = Int.MAX_VALUE
        session.seekTo(1_000)
        val failed = session.flushPersistence()
        assertIs<FlushResult.Failed>(failed)
        assertEquals("disk full", failed.cause.message)
    }

    @Test
    fun cancellingTheWorkerStopsItInsteadOfRetryingAndCancelledFlushesDoNotStopWrites() = runTest {
        val store = FakeListeningStore()
        val recorderScope = CoroutineScope(backgroundScope.coroutineContext + Job())
        val rig = rig(store, recorderScope = recorderScope)
        rig.session.initializePersistence()
        store.commitGate = CompletableDeferred()
        rig.session.play(listOf(yt("a")), startIndex = 0)
        runCurrent()
        val waiter = async { rig.session.flushPersistence() }
        runCurrent()
        waiter.cancel()
        runCurrent()
        assertEquals(1, store.attempts)

        checkNotNull(store.commitGate).complete(Unit)
        runCurrent()
        assertTrue(store.commits.isNotEmpty(), "a cancelled flush waiter does not cancel the write")

        store.commitGate = CompletableDeferred()
        rig.session.seekTo(5_000)
        runCurrent()
        val attempts = store.attempts
        recorderScope.cancel()
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(attempts, store.attempts, "a cancelled worker does not retry")
    }

    @Test
    fun coldRestoreStaysPausedAndCreatesNoActivity() = runTest {
        val store = FakeListeningStore(ListeningRestore(snapshot(yt("a"), yt("b"), currentIndex = 1, positionMs = 12_000), PublicationOrigin.LOCAL))
        val rig = rig(store)
        rig.session.initializePersistence()
        assertEquals("yt:b", rig.session.nowPlaying.value.track?.canonicalId)
        assertEquals(12_000, rig.session.nowPlaying.value.positionMs)
        assertFalse(rig.session.nowPlaying.value.isPlaying)

        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertEquals(FlushResult.Committed(), rig.session.flushPersistence())
        assertEquals(0, rig.engine.playCalls)
        assertEquals(SessionRevision(0), rig.session.localRevision)
        assertNull(store.export)
        assertTrue(store.commits.all { it.local == null })
    }

    @Test
    fun anActionTakenWhileTheRestoreReadIsSlowWinsOverTheRestore() = runTest {
        val store = FakeListeningStore(ListeningRestore(snapshot(yt("a"), yt("b")), PublicationOrigin.LOCAL))
        store.readGate = CompletableDeferred()
        val rig = rig(store)
        val startup = async { rig.session.initializePersistence() }
        runCurrent()
        rig.session.play(listOf(yt("x")), startIndex = 0)
        runCurrent()

        checkNotNull(store.readGate).complete(Unit)
        startup.await()
        assertEquals(listOf("yt:x"), rig.session.queueState.value.items.map { it.track.canonicalId })
        settle(rig)
        assertEquals("yt:x", store.latest.state.current?.track?.identity)
    }

    @Test
    fun unreadableRestoreStillFinishesStartup() = runTest {
        val store = FakeListeningStore().apply { readFailure = IllegalStateException("corrupt") }
        val rig = rig(store)
        rig.session.initializePersistence()
        assertTrue(rig.session.queueState.value.items.isEmpty())
        rig.session.play(listOf(yt("a")), startIndex = 0)
        settle(rig)
        assertEquals("yt:a", store.latest.state.current?.track?.identity)
    }

    @Test
    fun aRemoteLoadKeepsLocalActivityUntilTheUserSeeksOrEdits() = runTest {
        val rig = rig()
        val session = rig.session
        session.initializePersistence()
        session.play(listOf(yt("a")), startIndex = 0)
        runCurrent()
        session.pauseTransport()
        settle(rig)
        val local = rig.store.latest

        advanceTimeBy(5_000)
        assertTrue(session.loadRemotePaused(snapshot(yt("r1"), yt("r2")), session.localRevision))
        settle(rig)
        assertEquals(PublicationOrigin.REMOTE_SUPPRESSED, rig.store.restore.origin)
        assertEquals(listOf("yt:r1", "yt:r2"), rig.store.restore.snapshot.items.map { it.track.canonicalId })

        rig.engine.report(EngineStatus.IDLE)
        session.updateCurrentTrackArtwork("yt:r1", Artwork("https://img.example/r1.jpg"))
        advanceTimeBy(180_000)
        runCurrent()
        val beforePause = session.localRevision
        session.pauseTransport()
        assertEquals(SessionRevision(beforePause.value + 1), session.localRevision)
        settle(rig)
        assertEquals(local, rig.store.latest, "engine callbacks, metadata, time and a pause do not claim the remote queue")
        assertEquals(PublicationOrigin.REMOTE_SUPPRESSED, rig.store.restore.origin)

        session.seekTo(10_000)
        settle(rig)
        assertEquals(Row("q-r1", "yt:r1", 10_000, T0 + 185_000, false, T0 + 185_000), rig.store.latest.row())
        assertEquals(PublicationOrigin.LOCAL, rig.store.restore.origin)
    }

    @Test
    fun aPendingLocalChangeSurvivesARemoteLoadAndCheckpointAndPublishesOnce() = runTest {
        val rig = rig()
        val session = rig.session
        session.initializePersistence()
        session.play(listOf(yt("a")), startIndex = 0)
        settle(rig)
        val before = rig.store.publications.size

        rig.store.commitGate = CompletableDeferred()
        session.playNext(yt("z"))
        runCurrent()
        session.skipToNext()
        assertTrue(session.loadRemotePaused(snapshot(yt("r1")), session.localRevision))
        val checkpoint = async { session.flushPersistence() }
        runCurrent()
        checkNotNull(rig.store.commitGate).complete(Unit)
        assertEquals(FlushResult.Committed(), checkpoint.await())

        assertEquals(
            listOf("yt:a" to true, "yt:z" to false),
            rig.store.publications.drop(before).map { it.state.current?.track?.identity to it.state.isPlaying },
        )
        assertEquals(PublicationOrigin.REMOTE_SUPPRESSED, rig.store.restore.origin)
        settle(rig)
        assertEquals(before + 2, rig.store.publications.size, "the committed local change is not published again")

        val restarted = rig(rig.store)
        restarted.session.initializePersistence()
        assertEquals("yt:r1", restarted.session.nowPlaying.value.track?.canonicalId)
        restarted.session.pauseTransport()
        settle(restarted)
        assertEquals(before + 2, rig.store.publications.size, "suppression survives a restart")
        restarted.session.seekTo(2_000)
        settle(restarted)
        assertEquals("yt:r1", rig.store.latest.state.current?.track?.identity)
    }

    @Test
    fun heartbeatRecordsEveryMinuteOnlyWhileLocallyPlaying() = runTest {
        val rig = rig()
        rig.session.initializePersistence()
        rig.session.play(listOf(yt("a")), startIndex = 0)
        settle(rig)
        val published = rig.store.publications.size
        val commits = rig.store.commits.size

        rig.engine.report(positionMs = 59_000)
        advanceTimeBy(59_999)
        runCurrent()
        assertEquals(published, rig.store.publications.size)
        assertTrue(rig.store.commits.size >= commits + 5, "private checkpoints every 10 s")

        rig.engine.report(positionMs = 60_000)
        advanceTimeBy(1)
        settle(rig)
        assertEquals(published + 1, rig.store.publications.size)
        val id = rig.session.queueState.value.items.single().id
        assertEquals(Row(id, "yt:a", 60_000, T0 + 60_000, true, T0 + 60_000), rig.store.latest.row())

        rig.session.pauseTransport()
        settle(rig)
        advanceTimeBy(180_000)
        settle(rig)
        assertEquals(published + 2, rig.store.publications.size)
    }

    @Test
    fun everyQueueTransitionRecordsTheNewEntry() = runTest {
        val repeat = rig()
        repeat.session.initializePersistence()
        repeat.session.play(listOf(yt("a"), yt("b")), startIndex = 0)
        runCurrent()
        repeat.session.cycleRepeat()
        repeat.session.cycleRepeat()
        repeat.engine.report(positionMs = 100_000)
        settle(repeat)
        val beforeRestart = repeat.store.latest.revision
        repeat.engine.report(EngineStatus.ENDED)
        settle(repeat)
        assertEquals("yt:a" to 0L, repeat.store.latest.currentAndPosition(), "Repeat One restarts the entry at zero")
        assertTrue(repeat.store.latest.revision.value > beforeRestart.value)

        repeat.session.cycleRepeat()
        repeat.session.cycleRepeat()
        repeat.session.skipToNext()
        runCurrent()
        repeat.engine.report(EngineStatus.ENDED)
        settle(repeat)
        assertEquals("yt:a" to 0L, repeat.store.latest.currentAndPosition(), "Repeat All wraps")

        val continuation = rig(onQueueExhausted = { listOf(yt("d")) })
        continuation.session.initializePersistence()
        continuation.session.play(listOf(yt("a")), startIndex = 0)
        runCurrent()
        continuation.engine.report(EngineStatus.ENDED)
        settle(continuation)
        assertEquals("yt:d" to 0L, continuation.store.latest.currentAndPosition())

        val failing = rig()
        failing.session.initializePersistence()
        failing.session.play(listOf(yt("dead", playable = false), yt("b")), startIndex = 0)
        settle(failing)
        assertEquals("yt:b" to 0L, failing.store.latest.currentAndPosition(), "a dead entry is skipped")

        val removal = rig()
        removal.session.initializePersistence()
        removal.session.play(listOf(yt("a"), yt("b"), yt("c")), startIndex = 0)
        runCurrent()
        removal.session.removeFromQueue(removal.session.queueState.value.items.first().id)
        settle(removal)
        assertEquals("yt:b" to 0L, removal.store.latest.currentAndPosition())
        assertEquals(listOf("yt:b", "yt:c"), removal.store.latest.state.queue.entries.map { it.track.identity })
    }

    @Test
    fun rejectedCommandsDoNotAdvanceTheRevision() = runTest {
        val rig = rig()
        rig.session.initializePersistence()
        rig.session.play(listOf(yt("a"), yt("b")), startIndex = 0)
        runCurrent()
        val revision = rig.session.localRevision
        rig.session.moveInQueue(5, 9)
        rig.session.playQueueIndex(99)
        rig.session.removeFromQueue("missing")
        rig.engine.report(EngineStatus.PLAYING, positionMs = 1_000)
        runCurrent()
        assertEquals(revision, rig.session.localRevision)
    }

    @Test
    fun anUnexportableQueueRecordsUnavailabilityWithItsNewerActivity() = runTest {
        val rig = rig()
        rig.session.initializePersistence()
        rig.session.play(listOf(yt("a")), startIndex = 0)
        settle(rig)
        val published = rig.store.latest.revision

        advanceTimeBy(1_000)
        rig.session.play(listOf(track("Sample", "S", provider = ProviderId.SAMPLE)), startIndex = 0)
        runCurrent()
        assertEquals(FlushResult.Committed(ProjectionFailure.UNSUPPORTED_TRACK), rig.session.flushPersistence())
        assertEquals(
            LocalListeningExport.Unavailable(PublicationRevision(published.value + 2), T0 + 1_000, ProjectionFailure.UNSUPPORTED_TRACK),
            rig.store.export,
        )
    }

    @Test
    fun acceptedPausedPositionsSurviveLateSamplesAndBecomeTheResumePoint() = runTest {
        val restored = rig(FakeListeningStore(ListeningRestore(snapshot(yt("a"), positionMs = 12_000), PublicationOrigin.LOCAL)))
        restored.session.initializePersistence()
        restored.session.seekTo(30_000)
        restored.session.playTransport()
        runCurrent()
        assertEquals(30_000, restored.engine.seeks.last(), "Play resumes where the restored track was seeked to")

        val loaded = rig()
        loaded.session.initializePersistence()
        loaded.session.play(listOf(yt("a")), startIndex = 0)
        runCurrent()
        loaded.session.pauseTransport()
        runCurrent()
        loaded.engine.acknowledgeTransport = false
        loaded.session.seekTo(50_000)
        loaded.engine.report(EngineStatus.PAUSED, positionMs = 20_000)
        runCurrent()
        assertEquals(50_000, loaded.session.nowPlaying.value.positionMs)
        settle(loaded)
        assertEquals(50_000, loaded.store.restore.snapshot.positionMs)
        assertEquals(50_000, loaded.store.latest.state.positionMs)

        loaded.engine.acknowledgeTransport = true
        loaded.session.playTransport()
        loaded.engine.report(positionMs = 10_000)
        runCurrent()
        val beforePrevious = loaded.session.localRevision
        loaded.session.skipToPrevious()
        assertEquals(SessionRevision(beforePrevious.value + 1), loaded.session.localRevision)
        assertEquals(0, loaded.session.nowPlaying.value.positionMs)
    }

    private class Rig(val session: PlayerSession, val engine: ScriptedEngine, val store: FakeListeningStore)

    private fun TestScope.rig(
        store: FakeListeningStore = FakeListeningStore(),
        workerContext: CoroutineContext = EmptyCoroutineContext,
        recorderScope: CoroutineScope = backgroundScope,
        onQueueExhausted: (suspend (Set<String>) -> List<Track>)? = null,
    ): Rig {
        val engine = ScriptedEngine()
        val clock = { T0 + testScheduler.currentTime }
        val session = PlayerSession(
            engine = engine,
            resolver = DefaultSourceResolver(),
            scope = backgroundScope,
            onQueueExhausted = onQueueExhausted,
            persistence = NowPlayingRecorder(store, recorderScope, workerContext, clock),
            clock = clock,
        )
        return Rig(session, engine, store)
    }

    private suspend fun TestScope.settle(rig: Rig) {
        runCurrent()
        assertIs<FlushResult.Committed>(rig.session.flushPersistence())
    }
}

private const val T0 = 1_000_000L

private data class Row(
    val itemId: String?,
    val identity: String?,
    val positionMs: Long,
    val positionAtMs: Long,
    val isPlaying: Boolean,
    val lastActiveAtMs: Long,
)

private fun LocalListeningPublication.row() = Row(
    state.current?.itemId,
    state.current?.track?.identity,
    state.positionMs,
    state.positionAtMs,
    state.isPlaying,
    lastActiveAtMs,
)

private fun LocalListeningPublication.currentAndPosition() = state.current?.track?.identity to state.positionMs

private fun yt(id: String, playable: Boolean = true) =
    track(id, "Artist", provider = ProviderId.YOUTUBE_MUSIC, playable = playable, canonicalId = "yt:$id")

private fun snapshot(vararg tracks: Track, currentIndex: Int = 0, positionMs: Long = 0) = SessionSnapshot(
    items = tracks.map { PersistedQueueItem("q-" + it.canonicalId.removePrefix("yt:"), it.toPersisted(0)) },
    currentIndex = currentIndex,
    positionMs = positionMs,
)

/** Plays at once; transport acknowledgements can be withheld to model a slow engine. */
internal class ScriptedEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    var acknowledgeTransport = true
    val seeks = mutableListOf<Long>()
    var playCalls = 0
        private set

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        playCalls++
        state.value = EngineState(EngineStatus.PLAYING, durationMs = 200_000, playGeneration = playGeneration)
    }

    override fun pause() {
        if (acknowledgeTransport) state.update { it.copy(status = EngineStatus.PAUSED) }
    }

    override fun resume() {
        if (acknowledgeTransport) state.update { it.copy(status = EngineStatus.PLAYING) }
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        if (acknowledgeTransport) state.update { it.copy(positionMs = positionMs) }
    }

    override fun stop() = state.update { it.copy(status = EngineStatus.IDLE) }

    override fun setVolume(volume: Float) = Unit

    fun report(status: EngineStatus = state.value.status, positionMs: Long = state.value.positionMs) =
        state.update { it.copy(status = status, positionMs = positionMs) }
}

/** Runs worker tasks only when the test says so, to hold projection while the session keeps going. */
private class HeldDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}
