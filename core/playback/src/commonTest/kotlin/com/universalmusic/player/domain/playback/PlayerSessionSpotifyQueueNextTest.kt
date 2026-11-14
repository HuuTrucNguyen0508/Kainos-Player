package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionSpotifyQueueNextTest {
    private val first = track("First", "Artist", provider = ProviderId.SPOTIFY, durationMs = 180_000)
    private val second = track("Second", "Artist", provider = ProviderId.SPOTIFY, durationMs = 200_000)
    private val third = track("Third", "Artist", provider = ProviderId.SPOTIFY, durationMs = 200_000)

    @Test
    fun theNextSpotifyTrackIsQueuedAsSoonAsTheCurrentOneStarts() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second), startIndex = 0)
        runCurrent()

        assertEquals(listOf<String?>("SPOTIFY-Second"), engine.queued)
    }

    @Test
    fun theTrackThatIsPlayingIsNeverQueued() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, first.copy(canonicalId = "same recording")), startIndex = 0)
        runCurrent()
        session.play(listOf(second), startIndex = 0)
        runCurrent()
        session.setRepeatNow(RepeatMode.ONE)
        runCurrent()

        assertEquals(emptyList(), engine.queued)
    }

    @Test
    fun aSuccessorFromAnotherSourceClearsTheQueuedTrack() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second), startIndex = 0)
        runCurrent()
        session.playNext(track("Video", "Artist", provider = ProviderId.YOUTUBE_MUSIC))
        runCurrent()

        assertEquals(listOf("SPOTIFY-Second", null), engine.queued)
    }

    @Test
    fun aSpotifyTrackThatResolvesToALocalCopyIsNotQueued() = runTest {
        val (engine, session) = fixture()
        val local = track("Second", "Artist", provider = ProviderId.LOCAL).sources.single()
        session.play(listOf(first, second.copy(sources = listOf(local) + second.sources)), startIndex = 0)
        runCurrent()

        assertEquals(emptyList(), engine.queued)
    }

    @Test
    fun aSpotifyTrackAfterALocalOneIsNotQueued() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(track("Song", "Artist", provider = ProviderId.LOCAL), second), startIndex = 0)
        runCurrent()

        assertEquals(emptyList(), engine.queued)
    }

    @Test
    fun changingTheNextEntryRetargetsAtOnce() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second), startIndex = 0)
        runCurrent()

        session.playNext(third)
        runCurrent()
        // Appending leaves the next entry alone.
        session.addToQueue(second.copy(canonicalId = "appended"))
        runCurrent()

        assertEquals(listOf<String?>("SPOTIFY-Second", "SPOTIFY-Third"), engine.queued)
    }

    @Test
    fun shuffleRetargetsToTheNewNextEntry() = runTest {
        val (engine, session) = fixture()
        val tracks = (1..12).map { track("Song $it", "Artist", provider = ProviderId.SPOTIFY) }
        session.play(tracks, startIndex = 0)
        runCurrent()
        session.setShuffleNow(true)
        runCurrent()

        val next = session.queueState.value.upcoming.first()
        assertEquals("SPOTIFY-${next.track.title}", engine.queued.last())
        assertEquals("SPOTIFY-Song 2", engine.queued.first())
    }

    @Test
    fun clearingTheQueueClearsTheQueuedTrack() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second), startIndex = 0)
        runCurrent()
        session.clearQueue()
        runCurrent()

        assertEquals(listOf("SPOTIFY-Second", null), engine.queued)
    }

    @Test
    fun clearingUpcomingClearsTheQueuedTrack() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second), startIndex = 0)
        runCurrent()
        session.clearUpcoming()
        runCurrent()

        assertEquals(listOf("SPOTIFY-Second", null), engine.queued)
    }

    @Test
    fun aSleepTimerAtTrackEndClearsTheQueuedTrack() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second), startIndex = 0)
        runCurrent()
        session.setNaturalCompletionGate { true }
        runCurrent()

        assertEquals(listOf("SPOTIFY-Second", null), engine.queued)
    }

    @Test
    fun advancingIntoTheQueuedTrackKeepsItUntilItStartsThenQueuesItsSuccessor() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second, third), startIndex = 0)
        runCurrent()

        engine.state.value = engine.state.value.copy(status = EngineStatus.ENDED, positionMs = 180_000)
        runCurrent()

        assertEquals("Second", session.nowPlaying.value.track?.title)
        assertEquals(listOf<String?>("SPOTIFY-Second", "SPOTIFY-Third"), engine.queued)
        assertEquals(listOf("SPOTIFY-First", "SPOTIFY-Second"), engine.played)
    }

    private fun TestScope.fixture(): Pair<QueueNextEngine, PlayerSession> {
        val engine = QueueNextEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(networkAvailable = { true }), backgroundScope,
            clock = { testScheduler.currentTime })
        return engine to session
    }
}

private class QueueNextEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    val queued = mutableListOf<String?>()
    val played = mutableListOf<String>()
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        (handle as? PlaybackHandle.ProviderPlayback)?.let { played += it.trackId }
        state.value = EngineState(status = EngineStatus.PLAYING, playGeneration = playGeneration)
    }
    override fun queueNextSpotify(trackId: String?) { queued += trackId }
    override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
    override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun stop() { state.value = EngineState(playGeneration = state.value.playGeneration) }
    override fun setVolume(volume: Float) = Unit
}
