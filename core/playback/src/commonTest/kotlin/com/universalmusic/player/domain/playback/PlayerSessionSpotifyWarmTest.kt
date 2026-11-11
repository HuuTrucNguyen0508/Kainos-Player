package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionSpotifyWarmTest {
    private val first = track("First", "Artist", provider = ProviderId.SPOTIFY, durationMs = 180_000)
    private val second = track("Second", "Artist", provider = ProviderId.SPOTIFY, durationMs = 200_000)
    private val third = track("Third", "Artist", provider = ProviderId.SPOTIFY, durationMs = 200_000)

    @Test
    fun theNextSpotifyTrackIsWarmedOnceWhenAMinuteIsLeft() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second), startIndex = 0)
        runCurrent()

        at(engine, 119_000)
        assertEquals(emptyList(), engine.warms)
        at(engine, 120_000)
        at(engine, 150_000)
        at(engine, 179_000)
        assertEquals(listOf<String?>("SPOTIFY-Second"), engine.warms)
    }

    @Test
    fun aTrackShorterThanAMinuteWarmsItsSuccessorAtOnce() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first.copy(durationMs = 45_000), second), startIndex = 0)
        runCurrent()

        assertEquals(listOf<String?>("SPOTIFY-Second"), engine.warms)
    }

    @Test
    fun theTrackThatIsPlayingIsNeverWarmed() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, first.copy(canonicalId = "same recording")), startIndex = 0)
        runCurrent()

        at(engine, 170_000)
        assertEquals(emptyList(), engine.warms)
    }

    @Test
    fun aSuccessorFromAnotherSourceIsNotWarmed() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, track("Video", "Artist", provider = ProviderId.YOUTUBE_MUSIC)), startIndex = 0)
        runCurrent()

        at(engine, 170_000)
        assertEquals(emptyList(), engine.warms)
    }

    @Test
    fun changingTheNextEntryCancelsTheWarmAndRetargets() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second), startIndex = 0)
        runCurrent()
        at(engine, 130_000)

        session.playNext(third)
        runCurrent()
        at(engine, 131_000)
        session.addToQueue(second.copy(canonicalId = "appended"))
        runCurrent()
        at(engine, 132_000)

        assertEquals(listOf("SPOTIFY-Second", null, "SPOTIFY-Third"), engine.warms)
    }

    @Test
    fun aRetargetBeforeTheLastMinuteWaitsForIt() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second), startIndex = 0)
        runCurrent()
        at(engine, 60_000)

        session.playNext(third)
        runCurrent()
        at(engine, 100_000)
        assertEquals(emptyList(), engine.warms)
        at(engine, 125_000)
        assertEquals(listOf<String?>("SPOTIFY-Third"), engine.warms)
    }

    @Test
    fun advancingIntoTheWarmedTrackMovesOnToItsSuccessor() = runTest {
        val (engine, session) = fixture()
        session.play(listOf(first, second, third), startIndex = 0)
        runCurrent()
        at(engine, 130_000)

        engine.state.value = engine.state.value.copy(status = EngineStatus.ENDED)
        runCurrent()
        at(engine, 100_000)
        assertEquals(listOf("SPOTIFY-Second", null), engine.warms)
        at(engine, 150_000)

        assertEquals("Second", session.nowPlaying.value.track?.title)
        assertEquals(listOf("SPOTIFY-Second", null, "SPOTIFY-Third"), engine.warms)
    }

    private fun TestScope.fixture(): Pair<WarmEngine, PlayerSession> {
        val engine = WarmEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(networkAvailable = { true }), backgroundScope,
            clock = { testScheduler.currentTime })
        return engine to session
    }

    private fun TestScope.at(engine: WarmEngine, positionMs: Long) {
        engine.state.value = engine.state.value.copy(positionMs = positionMs)
        runCurrent()
    }
}

private class WarmEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    val warms = mutableListOf<String?>()
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        state.value = EngineState(status = EngineStatus.PLAYING, playGeneration = playGeneration)
    }
    override fun warmSpotify(trackId: String?) { warms += trackId }
    override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
    override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun stop() { state.value = EngineState(playGeneration = state.value.playGeneration) }
    override fun setVolume(volume: Float) = Unit
}
