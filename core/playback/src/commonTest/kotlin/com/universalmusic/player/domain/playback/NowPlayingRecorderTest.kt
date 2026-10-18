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
import com.universalmusic.player.domain.model.PlaybackSource
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
        assertEquals(stopped, timed.session.localRevision, "a redundant user pause changes nothing")
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
        assertFalse(session.loadRemote(snapshot(yt("r")), beforeSeek), "a remote load with a stale guard loses")
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
    fun anActionTakenBeforePersistenceInitializationWinsOverTheRestore() = runTest {
        val store = FakeListeningStore(ListeningRestore(snapshot(yt("saved")), PublicationOrigin.LOCAL))
        val rig = rig(store)
        rig.session.play(listOf(yt("chosen")), startIndex = 0)
        runCurrent()
        rig.session.pauseTransport()
        runCurrent()
        val acceptedQueue = rig.session.queueState.value
        val acceptedRevision = rig.session.localRevision

        rig.session.initializePersistence()
        assertEquals(acceptedQueue, rig.session.queueState.value)
        assertEquals(acceptedQueue.current?.id, rig.session.nowPlaying.value.queueItemId)
        assertEquals(acceptedRevision, rig.session.localRevision)
        settle(rig)
        assertEquals(listOf("yt:chosen"), store.restore.snapshot.items.map { it.track.canonicalId })
        assertEquals("yt:chosen", store.latest.state.current?.track?.identity)
        assertFalse(store.latest.state.isPlaying)
    }

    @Test
    fun nextOnAnEmptyQueueBeforeInitializationDoesNotDiscardTheRestore() = runTest {
        val store = FakeListeningStore(ListeningRestore(snapshot(yt("saved")), PublicationOrigin.LOCAL))
        val rig = rig(store)
        rig.session.skipToNext()
        assertEquals(SessionRevision(0), rig.session.localRevision)

        rig.session.initializePersistence()
        settle(rig)
        assertEquals("yt:saved", rig.session.nowPlaying.value.track?.canonicalId)
        assertNull(store.export)
        assertTrue(store.commits.all { it.local == null })
    }

    @Test
    fun nextOnAnEmptyQueueDuringRestoreDoesNotCreateActivityOrDiscardTheRestore() = runTest {
        val store = FakeListeningStore(ListeningRestore(snapshot(yt("saved")), PublicationOrigin.LOCAL))
        store.readGate = CompletableDeferred()
        val rig = rig(store)
        val startup = async { rig.session.initializePersistence() }
        runCurrent()
        rig.session.skipToNext()
        assertEquals(SessionRevision(0), rig.session.localRevision)
        assertTrue(store.commits.isEmpty())

        checkNotNull(store.readGate).complete(Unit)
        startup.await()
        settle(rig)
        assertEquals("yt:saved", rig.session.nowPlaying.value.track?.canonicalId)
        assertNull(store.export)
        assertTrue(store.commits.all { it.local == null })
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
        assertTrue(session.loadRemote(snapshot(yt("r1"), yt("r2")), session.localRevision))
        settle(rig)
        assertEquals(PublicationOrigin.REMOTE_SUPPRESSED, rig.store.restore.origin)
        assertEquals(listOf("yt:r1", "yt:r2"), rig.store.restore.snapshot.items.map { it.track.canonicalId })

        rig.engine.report(EngineStatus.IDLE)
        session.updateCurrentTrackArtwork("yt:r1", Artwork("https://img.example/r1.jpg"))
        advanceTimeBy(180_000)
        runCurrent()
        val beforePause = session.localRevision
        session.pauseTransport()
        assertEquals(beforePause, session.localRevision)
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
        session.pauseTransport()
        val load = async { session.loadRemote(snapshot(yt("r1")), session.localRevision) }
        runCurrent()
        checkNotNull(rig.store.commitGate).complete(Unit)
        assertTrue(load.await())
        val checkpoint = async { session.flushPersistence() }
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

    @Test
    fun idleSeekResumeDoesNotPublishInitialEngineZeroPositions() = runTest {
        val rig = rig(FakeListeningStore(ListeningRestore(snapshot(yt("a")), PublicationOrigin.LOCAL)))
        rig.session.initializePersistence()
        rig.engine.acknowledgeTransport = false
        rig.engine.bufferingGate = CompletableDeferred()
        rig.engine.playReturnGate = CompletableDeferred()
        rig.session.seekTo(178_000)
        settle(rig)
        assertEquals(178_000, rig.store.latest.state.positionMs)
        val beforePlay = rig.store.publications.size
        rig.session.playTransport()
        runCurrent()
        val bufferingPosition = rig.session.nowPlaying.value.positionMs
        checkNotNull(rig.engine.bufferingGate).complete(Unit)
        runCurrent()
        val playingPosition = rig.session.nowPlaying.value.positionMs
        val playingPublication = rig.store.latest
        val privatePosition = rig.store.restore.snapshot.positionMs
        checkNotNull(rig.engine.playReturnGate).complete(Unit)
        runCurrent()
        assertEquals(178_000, rig.engine.seeks.last())
        rig.engine.report(positionMs = 178_382)
        runCurrent()
        assertEquals(178_382, rig.session.nowPlaying.value.positionMs)
        assertTrue(rig.store.publications.size > beforePlay, "the playing publication is new")
        assertTrue(playingPublication.state.isPlaying)
        assertEquals(
            listOf(178_000L, 178_000L, 178_000L, 178_000L),
            listOf(bufferingPosition, playingPosition, playingPublication.state.positionMs, privatePosition),
            "accepted BUFFERING/PLAYING and freshly persisted local/private positions retain the seek",
        )
    }

    @Test
    fun coldResumeKeepsTheSavedPositionUntilItsSeekIsSubmitted() = runTest {
        val rig = rig(FakeListeningStore(ListeningRestore(snapshot(yt("a"), positionMs = 12_000), PublicationOrigin.LOCAL)))
        rig.session.initializePersistence()
        rig.engine.bufferingGate = CompletableDeferred()
        rig.engine.playReturnGate = CompletableDeferred()
        rig.session.playTransport()
        runCurrent()
        val bufferingPosition = rig.session.nowPlaying.value.positionMs
        checkNotNull(rig.engine.bufferingGate).complete(Unit)
        runCurrent()
        val playingPosition = rig.session.nowPlaying.value.positionMs
        val publishedPosition = rig.store.latest.state.positionMs
        checkNotNull(rig.engine.playReturnGate).complete(Unit)
        runCurrent()
        assertEquals(12_000, rig.engine.seeks.last())
        rig.engine.report(positionMs = 12_250)
        runCurrent()
        assertEquals(12_250, rig.session.nowPlaying.value.positionMs, "running samples replace the submitted resume target")
        rig.engine.report(positionMs = 11_500)
        runCurrent()
        assertEquals(11_500, rig.session.nowPlaying.value.positionMs, "the startup target does not clamp later samples")
        assertEquals(listOf(12_000L, 12_000L, 12_000L), listOf(bufferingPosition, playingPosition, publishedPosition))
    }

    @Test
    fun aSeekDuringBufferingSurvivesFlushAndPause() = runTest {
        val rig = rig(FakeListeningStore(ListeningRestore(snapshot(yt("a"), positionMs = 12_000), PublicationOrigin.LOCAL)))
        rig.session.initializePersistence()
        rig.engine.bufferingGate = CompletableDeferred()
        rig.session.playTransport()
        runCurrent()
        rig.session.seekTo(50_000)
        settle(rig)
        val flushed = rig.store.restore.snapshot.positionMs
        val visible = rig.session.nowPlaying.value.positionMs
        rig.session.pauseTransport()
        settle(rig)
        assertEquals(listOf(50_000L, 50_000L, 50_000L, 50_000L),
            listOf(visible, flushed, rig.store.restore.snapshot.positionMs, rig.store.latest.state.positionMs))
        assertFalse(rig.store.latest.state.isPlaying)
    }

    @Test
    fun aNewerSeekReplacesTheDeferredResumeSeek() = runTest {
        val rig = rig(FakeListeningStore(ListeningRestore(snapshot(yt("a"), positionMs = 12_000), PublicationOrigin.LOCAL)))
        rig.session.initializePersistence()
        rig.engine.bufferingGate = CompletableDeferred()
        rig.engine.playReturnGate = CompletableDeferred()
        rig.session.playTransport()
        runCurrent()
        rig.session.seekTo(50_000)
        runCurrent()
        checkNotNull(rig.engine.bufferingGate).complete(Unit)
        runCurrent()
        val published = rig.store.latest.state.positionMs
        checkNotNull(rig.engine.playReturnGate).complete(Unit)
        settle(rig)
        assertEquals(listOf(50_000L, 50_000L), rig.engine.seeks)
        assertEquals(50_000, published)
        assertEquals(50_000, rig.session.nowPlaying.value.positionMs)
        assertEquals(50_000, rig.store.restore.snapshot.positionMs)
        rig.engine.report(positionMs = 50_250)
        runCurrent()
        assertEquals(50_250, rig.session.nowPlaying.value.positionMs)
    }

    @Test
    fun aSeekToZeroReplacesTheDeferredResumeSeek() = runTest {
        val rig = rig(FakeListeningStore(ListeningRestore(snapshot(yt("a"), positionMs = 12_000), PublicationOrigin.LOCAL)))
        rig.session.initializePersistence()
        rig.engine.bufferingGate = CompletableDeferred()
        rig.session.playTransport()
        runCurrent()
        rig.session.seekTo(0)
        runCurrent()
        checkNotNull(rig.engine.bufferingGate).complete(Unit)
        settle(rig)
        assertEquals(listOf(0L, 0L), rig.engine.seeks)
        assertEquals(0, rig.session.nowPlaying.value.positionMs)
        assertEquals(0, rig.store.restore.snapshot.positionMs)
        assertEquals(0, rig.store.latest.state.positionMs)
    }

    @Test
    fun aFallbackSubmitsThePositionItPublishesDuringStartup() = runTest {
        val combined = track("Song", "Artist", provider = ProviderId.SPOTIFY).let {
            it.copy(canonicalId = "yt:mirror", sources = it.sources + yt("mirror").sources)
        }
        val rig = rig(
            store = FakeListeningStore(ListeningRestore(snapshot(combined, positionMs = 12_000), PublicationOrigin.LOCAL)),
            enrichSource = { _, source ->
                if (source.provider == ProviderId.SPOTIFY) error("preferred source unavailable")
                source
            },
        )
        rig.session.initializePersistence()
        rig.engine.playReturnGate = CompletableDeferred()
        rig.session.playTransport()
        runCurrent()
        val publication = rig.store.latest
        assertEquals(ProviderId.YOUTUBE_MUSIC, rig.session.nowPlaying.value.resolved?.source?.provider)
        assertTrue(publication.state.isPlaying)
        checkNotNull(rig.engine.playReturnGate).complete(Unit)
        settle(rig)
        assertEquals(listOf(12_000L), rig.engine.seeks, "fallback must submit the published resume target")
        assertEquals(12_000, publication.state.positionMs)
        assertEquals(12_000, rig.engine.state.value.positionMs)
        assertEquals(12_000, rig.store.restore.snapshot.positionMs)
    }

    @Test
    fun switchingSourceDuringStartupKeepsTheLatestAcceptedTarget() = runTest {
        assertStartupFallback(engineFailure = false)
    }

    @Test
    fun aFailedStartupKeepsTheLatestTargetThroughFallback() = runTest {
        assertStartupFallback(engineFailure = true)
    }

    private suspend fun TestScope.assertStartupFallback(engineFailure: Boolean) {
        val combined = track("Song", "Artist", provider = ProviderId.SPOTIFY).let {
            it.copy(canonicalId = "yt:mirror", sources = it.sources + yt("mirror").sources)
        }
        val rig = rig(FakeListeningStore(ListeningRestore(snapshot(combined, positionMs = 12_000), PublicationOrigin.LOCAL)))
        rig.session.initializePersistence()
        rig.engine.playReturnGate = CompletableDeferred()
        rig.session.playTransport()
        runCurrent()
        rig.session.seekTo(50_000)
        runCurrent()
        if (engineFailure) rig.engine.report(EngineStatus.FAILED) else rig.session.tryAnotherSource()
        runCurrent()
        val publication = rig.store.latest
        checkNotNull(rig.engine.playReturnGate).complete(Unit)
        settle(rig)
        assertEquals(ProviderId.YOUTUBE_MUSIC, rig.session.nowPlaying.value.resolved?.source?.provider)
        assertEquals(listOf(50_000L, 50_000L), rig.engine.seeks)
        assertEquals(50_000, publication.state.positionMs)
        assertEquals(50_000, rig.engine.state.value.positionMs)
        assertEquals(50_000, rig.store.restore.snapshot.positionMs)
        rig.session.pauseTransport()
        settle(rig)
    }

    @Test
    fun aNewSelectionDiscardsTheUnsubmittedResumePositionAndItsOldSamples() = runTest {
        val rig = rig(FakeListeningStore(ListeningRestore(snapshot(yt("a"), positionMs = 12_000), PublicationOrigin.LOCAL)))
        rig.session.initializePersistence()
        rig.engine.bufferingGate = CompletableDeferred()
        rig.session.playTransport()
        runCurrent()
        val oldSample = rig.engine.state.value.copy(status = EngineStatus.PLAYING, positionMs = 12_000)
        rig.engine.bufferingGate = null
        rig.session.play(yt("b"))
        settle(rig)
        val selected = rig.session.queueState.value.current?.id
        assertEquals("yt:b", rig.session.nowPlaying.value.track?.canonicalId)
        assertEquals(0, rig.session.nowPlaying.value.positionMs)
        rig.engine.state.value = oldSample
        settle(rig)
        assertEquals(selected, rig.session.nowPlaying.value.queueItemId)
        assertEquals(0, rig.store.restore.snapshot.positionMs)
        assertEquals(0, rig.store.latest.state.positionMs)
        assertTrue(rig.engine.seeks.isEmpty(), "the superseded resume must not seek the new track")
    }

    @Test
    fun immediatePauseThenSeekSurvivesDelayedSamplesAndRestoresTheResumePosition() = runTest {
        val rig = rig()
        rig.session.initializePersistence()
        rig.session.play(listOf(yt("a")), startIndex = 0)
        settle(rig)
        rig.engine.report(positionMs = 20_000)
        runCurrent()
        rig.engine.acknowledgeTransport = false
        val beforePause = rig.session.localRevision

        rig.session.pauseTransport()
        rig.session.seekTo(50_000)
        assertEquals(SessionRevision(beforePause.value + 2), rig.session.localRevision)
        assertFalse(rig.session.nowPlaying.value.isPlaying)
        assertEquals(50_000, rig.session.nowPlaying.value.positionMs)
        rig.engine.report(EngineStatus.PLAYING, positionMs = 20_500)
        settle(rig)
        assertFalse(rig.session.nowPlaying.value.isPlaying)
        assertEquals(50_000, rig.session.nowPlaying.value.positionMs)
        assertFalse(rig.store.latest.state.isPlaying)
        assertEquals(50_000, rig.store.latest.state.positionMs)
        rig.engine.report(EngineStatus.PAUSED, positionMs = 20_000)
        runCurrent()
        rig.engine.report(EngineStatus.PAUSED, positionMs = 21_000)
        runCurrent()
        assertFalse(rig.session.nowPlaying.value.isPlaying)
        assertEquals(50_000, rig.session.nowPlaying.value.positionMs)
        settle(rig)
        assertEquals(50_000, rig.store.readRestore().snapshot.positionMs)
        assertEquals(50_000, rig.store.latest.state.positionMs)
        assertFalse(rig.store.latest.state.isPlaying)

        val restarted = rig(rig.store)
        restarted.session.initializePersistence()
        assertEquals(50_000, restarted.session.nowPlaying.value.positionMs)
        assertFalse(restarted.session.nowPlaying.value.isPlaying)
        restarted.session.playTransport()
        settle(restarted)
        assertEquals(50_000, restarted.engine.seeks.last())
        assertTrue(restarted.store.latest.state.isPlaying)
    }

    @Test
    fun redundantPausesDoNotCreateActivityWhileCallbacksAreDelayedOrAfterAcknowledgement() = runTest {
        val rig = rig()
        rig.session.initializePersistence()
        rig.session.play(listOf(yt("a")), startIndex = 0)
        settle(rig)
        rig.engine.acknowledgeTransport = false
        rig.session.pauseTransport()
        val pausedRevision = rig.session.localRevision
        settle(rig)
        val pausedPublication = rig.store.latest
        val publicationCount = rig.store.publications.size

        advanceTimeBy(1_000)
        rig.session.pauseTransport()
        rig.engine.report(EngineStatus.PLAYING, positionMs = 1_000)
        runCurrent()
        rig.session.pauseTransport()
        assertEquals(pausedRevision, rig.session.localRevision)
        settle(rig)
        assertEquals(pausedPublication, rig.store.latest)
        assertEquals(publicationCount, rig.store.publications.size)

        rig.engine.report(EngineStatus.BUFFERING, positionMs = 1_000)
        runCurrent()
        rig.session.pauseTransport()
        assertEquals(pausedRevision, rig.session.localRevision)
        rig.engine.report(EngineStatus.PAUSED, positionMs = 1_000)
        runCurrent()
        rig.session.pauseTransport()
        assertEquals(pausedRevision, rig.session.localRevision)
        settle(rig)
        assertEquals(pausedPublication, rig.store.latest)
        assertEquals(publicationCount, rig.store.publications.size)
    }

    @Test
    fun redundantPausesOnColdAndRemotePausedQueuesDoNotRecordOrReclaimActivity() = runTest {
        val rig = rig(FakeListeningStore(ListeningRestore(snapshot(yt("saved")), PublicationOrigin.LOCAL)))
        rig.session.initializePersistence()
        repeat(2) { rig.session.pauseTransport() }
        assertEquals(SessionRevision(0), rig.session.localRevision)
        settle(rig)
        assertNull(rig.store.export)

        assertTrue(rig.session.loadRemote(snapshot(yt("remote")), rig.session.localRevision))
        runCurrent()
        val remoteRevision = rig.session.localRevision
        repeat(2) { rig.session.pauseTransport() }
        assertEquals(remoteRevision, rig.session.localRevision)
        settle(rig)
        assertNull(rig.store.export)
        assertEquals(PublicationOrigin.REMOTE_SUPPRESSED, rig.store.readRestore().origin)
    }

    @Test
    fun flushBeforeAndDuringInitializationReportsFailureUntilTheWorkerCanPersist() = runTest {
        val store = FakeListeningStore(ListeningRestore(snapshot(yt("saved")), PublicationOrigin.LOCAL))
        val rig = rig(store)
        rig.session.addToQueue(yt("chosen"))
        assertIs<FlushResult.Failed>(rig.session.flushPersistence())
        assertTrue(store.commits.isEmpty())
        store.readGate = CompletableDeferred()
        val startup = async { rig.session.initializePersistence() }
        runCurrent()
        assertIs<FlushResult.Failed>(rig.session.flushPersistence())
        assertTrue(store.commits.isEmpty())

        checkNotNull(store.readGate).complete(Unit)
        startup.await()
        settle(rig)
        assertEquals(listOf("yt:chosen"), store.readRestore().snapshot.items.map { it.track.canonicalId })
        rig.session.playQueueIndex(0)
        settle(rig)
        assertEquals("yt:chosen", store.latest.state.current?.track?.identity)
        assertTrue(store.latest.state.isPlaying)
    }

    @Test
    fun aStalePlayingTickCannotUndoAnAcceptedPause() = runTest {
        val rig = rig()
        rig.session.initializePersistence()
        rig.session.play(listOf(yt("a")), startIndex = 0)
        settle(rig)
        rig.engine.acknowledgeTransport = false
        rig.session.pauseTransport()
        val pausedRevision = rig.session.localRevision
        rig.engine.report(EngineStatus.PLAYING, positionMs = 1_000)
        settle(rig)
        assertEquals(pausedRevision, rig.session.localRevision)
        assertFalse(rig.session.nowPlaying.value.isPlaying)
        assertFalse(rig.store.latest.state.isPlaying)

        rig.session.playTransport()
        assertTrue(rig.session.nowPlaying.value.isPlaying)
        settle(rig)
        assertTrue(rig.store.latest.state.isPlaying)
    }

    @Test
    fun effectivePauseCancelsAnUnacknowledgedResume() = runTest {
        val rig = rig()
        rig.session.initializePersistence()
        rig.session.play(listOf(yt("a")), startIndex = 0)
        settle(rig)
        rig.session.pauseTransport()
        settle(rig)
        assertEquals(EngineStatus.PAUSED, rig.engine.actualTransportStatus)
        rig.engine.deferTransport = true
        val beforePlay = rig.session.localRevision

        rig.session.playTransport()
        assertTrue(rig.session.nowPlaying.value.isPlaying)
        assertEquals(EngineStatus.PAUSED, rig.engine.state.value.status)
        rig.session.pauseTransport()
        assertFalse(rig.session.nowPlaying.value.isPlaying)
        assertEquals(SessionRevision(beforePlay.value + 2), rig.session.localRevision)
        settle(rig)
        rig.engine.drainTransport()
        settle(rig)
        assertEquals(EngineStatus.PAUSED, rig.engine.actualTransportStatus, "accepted Pause must cancel the queued Resume")
        assertFalse(rig.session.nowPlaying.value.isPlaying)
        val restored = rig(rig.store)
        restored.session.initializePersistence()
        assertFalse(restored.session.nowPlaying.value.isPlaying)
        assertEquals(rig.session.queueState.value.current?.id, restored.session.queueState.value.current?.id)
        assertFalse(assertIs<LocalListeningExport.Available>(rig.store.readLocalExport()).publication.state.isPlaying)

        val pausedRevision = rig.session.localRevision
        val pausedPublication = rig.store.latest
        rig.session.pauseTransport()
        rig.engine.drainTransport()
        settle(rig)
        assertEquals(pausedRevision, rig.session.localRevision)
        assertEquals(pausedPublication, rig.store.latest)

        rig.session.playTransport()
        rig.engine.drainTransport()
        settle(rig)
        assertEquals(EngineStatus.PLAYING, rig.engine.actualTransportStatus)
        assertTrue(rig.session.nowPlaying.value.isPlaying)
        assertTrue(rig.store.latest.state.isPlaying)
        rig.session.pauseTransport()
        rig.engine.drainTransport()
        settle(rig)
        assertEquals(EngineStatus.PAUSED, rig.engine.actualTransportStatus)
        assertFalse(rig.store.latest.state.isPlaying)
    }

    @Test
    fun durationSleepTimerCancelsAnUnacknowledgedResume() = runTest {
        val rig = rig()
        rig.session.initializePersistence()
        rig.session.play(listOf(yt("a")), startIndex = 0)
        settle(rig)
        rig.session.pauseTransport()
        settle(rig)
        rig.engine.deferTransport = true
        rig.session.playTransport()
        settle(rig)
        val timer = SleepTimerController(rig.session, backgroundScope) { testScheduler.currentTime }
        timer.startDuration(1_000)
        runCurrent()

        advanceTimeBy(1_000)
        settle(rig)
        assertFalse(timer.state.value.active)
        assertFalse(rig.session.nowPlaying.value.isPlaying)
        rig.engine.drainTransport()
        settle(rig)
        assertEquals(EngineStatus.PAUSED, rig.engine.actualTransportStatus, "duration timer must cancel the queued Resume")
        val restored = rig(rig.store)
        restored.session.initializePersistence()
        assertFalse(restored.session.nowPlaying.value.isPlaying)
        assertEquals(rig.session.queueState.value.current?.id, restored.session.queueState.value.current?.id)
        assertFalse(assertIs<LocalListeningExport.Available>(rig.store.readLocalExport()).publication.state.isPlaying)
        assertEquals(T0 + 1_000, rig.store.latest.lastActiveAtMs, "timer stop records the final active time")
    }

    @Test
    fun playAfterAnUnacknowledgedResumeAndPauseFinishesPlaying() = runTest {
        val rig = rig()
        rig.session.initializePersistence()
        rig.session.play(listOf(yt("a")), startIndex = 0)
        settle(rig)
        rig.session.pauseTransport()
        settle(rig)
        rig.engine.deferTransport = true

        rig.session.playTransport()
        rig.session.pauseTransport()
        rig.session.playTransport()
        rig.engine.drainTransport()
        settle(rig)
        assertEquals(EngineStatus.PLAYING, rig.engine.actualTransportStatus)
        assertTrue(rig.session.nowPlaying.value.isPlaying)
        assertEquals(rig.session.queueState.value.current?.id, rig.store.readRestore().snapshot.items.single().id)
        assertTrue(assertIs<LocalListeningExport.Available>(rig.store.readLocalExport()).publication.state.isPlaying)
    }

    @Test
    fun flushDuringADelayedInitializationDoesNotClaimPendingCapturesAreCommitted() = runTest {
        val store = FakeListeningStore().apply { readGate = CompletableDeferred() }
        val rig = rig(store)
        val startup = async { rig.session.initializePersistence() }
        runCurrent()
        rig.session.addToQueue(yt("chosen"))
        assertIs<FlushResult.Failed>(rig.session.flushPersistence())
        assertTrue(store.commits.isEmpty())

        checkNotNull(store.readGate).complete(Unit)
        startup.await()
        settle(rig)
        assertEquals(listOf("yt:chosen"), store.readRestore().snapshot.items.map { it.track.canonicalId })
    }

    private class Rig(val session: PlayerSession, val engine: ScriptedEngine, val store: FakeListeningStore)

    private fun TestScope.rig(
        store: FakeListeningStore = FakeListeningStore(),
        workerContext: CoroutineContext = EmptyCoroutineContext,
        recorderScope: CoroutineScope = backgroundScope,
        onQueueExhausted: (suspend (Set<String>) -> List<Track>)? = null,
        enrichSource: suspend (Track, PlaybackSource) -> PlaybackSource = { _, source -> source },
    ): Rig {
        val engine = ScriptedEngine()
        val clock = { T0 + testScheduler.currentTime }
        val session = PlayerSession(
            engine = engine,
            resolver = DefaultSourceResolver(),
            scope = backgroundScope,
            onQueueExhausted = onQueueExhausted,
            enrichSource = enrichSource,
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

private var remoteRevision = 0L

private suspend fun PlayerSession.loadRemote(snapshot: SessionSnapshot, expected: SessionRevision): Boolean =
    loadRemotePaused(snapshot, peerHandoffOf(snapshot, ++remoteRevision), expected, FakeExchange(), null) is HandoffDecision.Load

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

internal class ScriptedEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    var acknowledgeTransport = true
    var deferTransport = false
    var bufferingGate: CompletableDeferred<Unit>? = null
    var playReturnGate: CompletableDeferred<Unit>? = null
    var actualTransportStatus = EngineStatus.IDLE
        private set
    private val transportCommands = ArrayDeque<EngineStatus>()
    val seeks = mutableListOf<Long>()
    var playCalls = 0
        private set

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        playCalls++
        bufferingGate?.let {
            state.value = EngineState(EngineStatus.BUFFERING, playGeneration = playGeneration)
            it.await()
        }
        actualTransportStatus = EngineStatus.PLAYING
        state.value = EngineState(EngineStatus.PLAYING, durationMs = 200_000, playGeneration = playGeneration)
        playReturnGate?.await()
    }

    override fun pause() = submitTransport(EngineStatus.PAUSED)

    override fun resume() = submitTransport(EngineStatus.PLAYING)

    private fun submitTransport(status: EngineStatus) {
        if (deferTransport) transportCommands.addLast(status) else applyTransport(status)
    }

    private fun applyTransport(status: EngineStatus) {
        actualTransportStatus = status
        if (acknowledgeTransport) state.update { it.copy(status = status) }
    }

    fun drainTransport() {
        while (transportCommands.isNotEmpty()) applyTransport(transportCommands.removeFirst())
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
        if (acknowledgeTransport) state.update { it.copy(positionMs = positionMs) }
    }

    override fun stop() {
        actualTransportStatus = EngineStatus.IDLE
        state.update { it.copy(status = EngineStatus.IDLE) }
    }

    override fun setVolume(volume: Float) = Unit

    fun report(status: EngineStatus = state.value.status, positionMs: Long = state.value.positionMs) =
        state.update { it.copy(status = status, positionMs = positionMs) }
}

private class HeldDispatcher : CoroutineDispatcher() {
    private val tasks = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        tasks.addLast(block)
    }

    fun runAll() {
        while (tasks.isNotEmpty()) tasks.removeFirst().run()
    }
}
