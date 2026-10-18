package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PublicationOrigin
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionRemoteLoadTest {
    @Test
    fun aLoadWhilePlayingDoesNothing() = runTest {
        val rig = rig()
        rig.session.play(listOf(yt("a"), yt("b")), startIndex = 0)
        runCurrent()

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.LOCAL_PLAYING), rig.load(remote("r1"), revision = 1))
        assertEquals(listOf("yt:a", "yt:b"), rig.queueIds())
        assertEquals(EngineStatus.PLAYING, rig.engine.state.value.status)
    }

    @Test
    fun aLoadWhileBufferingDoesNothing() = runTest {
        val rig = rig()
        rig.engine.bufferingGate = CompletableDeferred()
        rig.session.play(listOf(yt("a")), startIndex = 0)
        runCurrent()

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.LOCAL_BUFFERING), rig.load(remote("r1"), revision = 1))
        assertEquals(listOf("yt:a"), rig.queueIds())
    }

    @Test
    fun aLoadWithAStaleGuardDoesNothing() = runTest {
        val rig = rig()
        pausedOn(rig, yt("a"))
        val view = rig.session.handoffView()
        rig.session.seekTo(5_000)

        assertEquals(
            HandoffDecision.Skip(HandoffSkipReason.LOCAL_CHANGED),
            rig.session.loadRemotePaused(remote("r1"), peerHandoffOf(remote("r1"), 1), view.revision),
        )
        assertEquals(listOf("yt:a"), rig.queueIds())
        assertEquals(5_000, rig.session.nowPlaying.value.positionMs)
    }

    @Test
    fun revisionTenFinishingAfterElevenDoesNothing() = runTest {
        val rig = rig()
        pausedOn(rig, yt("a"))
        val newer = rig.load(remote("r11"), revision = 11)

        assertEquals(HandoffDecision.Skip(HandoffSkipReason.ALREADY_APPLIED), rig.load(remote("r10"), revision = 10))
        assertEquals(HandoffDecision.Load(peerHandoffOf(remote("r11"), 11)), newer)
        assertEquals(listOf("yt:r11"), rig.queueIds())
    }

    @Test
    fun aPausedLoadReplacesTheQueueAndPositionWithoutLocalActivity() = runTest {
        val rig = rig()
        pausedOn(rig, yt("a"))
        val before = rig.session.localRevision
        val stops = rig.engine.transitionStops
        val plays = rig.engine.plays.size
        val remote = remote("r1", "r2", currentIndex = 1, positionMs = 42_000)

        assertEquals(HandoffDecision.Load(peerHandoffOf(remote, 1)), rig.load(remote, revision = 1))

        assertEquals(listOf("yt:r1", "yt:r2"), rig.queueIds())
        val now = rig.session.nowPlaying.value
        assertEquals("yt:r2" to 42_000L, now.track?.canonicalId to now.positionMs)
        assertFalse(now.isPlaying)
        assertEquals(stops + 1, rig.engine.transitionStops)
        assertEquals(before.value + 1, rig.session.localRevision.value)
        assertEquals(plays, rig.engine.plays.size, "a remote load never starts audio")
    }

    @Test
    fun playAfterALoadResolvesTheNewTrackNotTheEnginesOldOne() = runTest {
        val rig = rig()
        pausedOn(rig, yt("a"))
        assertEquals(EngineStatus.PAUSED, rig.engine.state.value.status, "the engine still holds the old paused track")

        rig.load(remote("r1", positionMs = 42_000), revision = 1)
        rig.session.playTransport()
        runCurrent()

        assertEquals(0, rig.engine.resumes)
        assertEquals(
            listOf("YOUTUBE_MUSIC-a", "youtube_music-r1"),
            rig.engine.plays.map { (it as PlaybackHandle.ProviderPlayback).trackId },
        )
        assertEquals(listOf(42_000L), rig.engine.seeks)
        assertEquals("yt:r1", rig.session.nowPlaying.value.track?.canonicalId)
    }

    @Test
    fun aRemoteLoadIsPersistedAsSuppressedPrivateState() = runTest {
        val store = FakeListeningStore()
        val rig = rig(store)
        rig.session.initializePersistence()
        pausedOn(rig, yt("a"))
        runCurrent()
        assertIs<FlushResult.Committed>(rig.session.flushPersistence())
        val publications = store.publications.size

        rig.load(remote("r1"), revision = 1)
        assertIs<FlushResult.Committed>(rig.session.flushPersistence())

        assertEquals(PublicationOrigin.REMOTE_SUPPRESSED, store.restore.origin)
        assertEquals(listOf("yt:r1"), store.restore.snapshot.items.map { it.track.canonicalId })
        assertEquals(publications, store.publications.size)
    }

    private class Rig(val session: PlayerSession, val engine: LaggingEngine) {
        fun queueIds() = session.queueState.value.items.map { it.track.canonicalId }

        suspend fun load(snapshot: SessionSnapshot, revision: Long) =
            session.loadRemotePaused(snapshot, peerHandoffOf(snapshot, revision), session.handoffView().revision)
    }

    private fun TestScope.rig(store: FakeListeningStore? = null): Rig {
        val engine = LaggingEngine()
        val session = PlayerSession(
            engine = engine,
            resolver = DefaultSourceResolver(),
            scope = backgroundScope,
            persistence = store?.let { NowPlayingRecorder(it, backgroundScope, clock = { testScheduler.currentTime }) },
            clock = { testScheduler.currentTime },
        )
        return Rig(session, engine)
    }

    private fun TestScope.pausedOn(rig: Rig, track: Track) {
        rig.session.play(listOf(track), startIndex = 0)
        runCurrent()
        rig.session.pauseTransport()
        runCurrent()
    }
}

private class LaggingEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    var bufferingGate: CompletableDeferred<Unit>? = null
    val plays = mutableListOf<PlaybackHandle>()
    val seeks = mutableListOf<Long>()
    var resumes = 0
        private set
    var transitionStops = 0
        private set

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        plays += handle
        bufferingGate?.let {
            state.value = EngineState(EngineStatus.BUFFERING, playGeneration = playGeneration)
            it.await()
        }
        state.value = EngineState(EngineStatus.PLAYING, durationMs = 200_000, playGeneration = playGeneration)
    }

    override fun pause() = state.update { it.copy(status = EngineStatus.PAUSED) }

    override fun resume() {
        resumes++
        state.update { it.copy(status = EngineStatus.PLAYING) }
    }

    override fun seekTo(positionMs: Long) {
        seeks += positionMs
    }

    override fun stop() = state.update { it.copy(status = EngineStatus.IDLE) }

    override fun stopForTransition() {
        transitionStops++
    }

    override fun setVolume(volume: Float) = Unit
}

private fun yt(id: String) = track(id, "Artist", provider = ProviderId.YOUTUBE_MUSIC, canonicalId = "yt:$id")

private fun remote(vararg ids: String, currentIndex: Int = 0, positionMs: Long = 0) = SessionSnapshot(
    items = ids.map { PersistedQueueItem("q-$it", yt(it).toPersisted(0)) },
    currentIndex = currentIndex,
    positionMs = positionMs,
)
