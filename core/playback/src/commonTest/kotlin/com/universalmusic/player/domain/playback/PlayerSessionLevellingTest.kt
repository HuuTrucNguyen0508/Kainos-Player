package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionLevellingTest {
    /** Reports levelling the way the real engines do: on play, at a gapless switch, and on a toggle. */
    private class Engine : PlaybackEngine {
        override val supportsGapless = true
        override val state = MutableStateFlow(EngineState())
        var next: PreparedPlayback? = null
        var levelling = true
        override fun configureLevelling(enabled: Boolean) {
            levelling = enabled
            if (state.value.levelling != null) state.value = state.value.copy(levelling = if (enabled) TAGGED else Levelling.Off)
        }
        override fun prepareNext(next: PreparedPlayback?) { this.next = next }
        override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
            state.value = EngineState(EngineStatus.PLAYING, playGeneration = playGeneration,
                levelling = if (levelling) TAGGED else Levelling.Off)
        }
        fun switchToUntagged() {
            val prepared = requireNotNull(next)
            state.value = EngineState(EngineStatus.PLAYING, playGeneration = prepared.generation + 1,
                previousGeneration = prepared.generation, seamlessItemId = prepared.itemId, levelling = Levelling.Untagged)
        }
        fun end() { state.value = state.value.copy(status = EngineStatus.ENDED) }
        override fun pause() = Unit
        override fun resume() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun stop() { next = null; state.value = EngineState(playGeneration = state.value.playGeneration) }
        override fun setVolume(volume: Float) = Unit
    }

    private fun local(title: String): Track {
        val track = track(title, "Artist", provider = ProviderId.LOCAL)
        return track.copy(sources = track.sources.map { it.copy(handle = PlaybackHandle.Url("/$title.flac")) })
    }

    @Test fun nowPlayingFollowsWhatTheEngineAppliedAcrossAGaplessSwitch() = runTest {
        val engine = Engine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(listOf(local("A"), local("B")))
        runCurrent()
        assertEquals(TAGGED, session.nowPlaying.value.levelling)
        engine.switchToUntagged()
        runCurrent()
        assertEquals("B", session.nowPlaying.value.track?.title)
        assertEquals(Levelling.Untagged, session.nowPlaying.value.levelling)
    }

    @Test fun theSettingReachesTheEngineAndTheTrackPlayingNow() = runTest {
        val engine = Engine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope,
            initialPreferences = PlaybackPreferences(volumeLevelling = false))
        session.play(listOf(local("A")))
        runCurrent()
        assertEquals(Levelling.Off, session.nowPlaying.value.levelling)
        session.updatePreferences(PlaybackPreferences(volumeLevelling = true))
        runCurrent()
        assertTrue(engine.levelling)
        assertEquals(TAGGED, session.nowPlaying.value.levelling)
    }

    @Test fun nothingIsClaimedOnceThePlayerStopsAtTheQueueEnd() = runTest {
        val engine = Engine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(listOf(local("A")))
        runCurrent()
        engine.end()
        runCurrent()
        assertEquals("A", session.nowPlaying.value.track?.title)
        assertNull(session.nowPlaying.value.levelling)
    }

    private companion object {
        val TAGGED = Levelling.Applied(-6.0)
    }
}
