package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionFallbackTest {
    @Test
    fun fallsBackWhenPreferredSourceFailsToStart() = runTest {
        val failing = ProviderId.SPOTIFY
        val engine = RecordingEngine { handle ->
            if (handle is PlaybackHandle.ProviderPlayback && handle.provider == failing) {
                error("spotify connect unavailable")
            }
        }
        val session = PlayerSession(
            engine = engine,
            resolver = DefaultSourceResolver(),
            scope = backgroundScope,
            initialPreferences = PlaybackPreferences(preferredProvider = ProviderId.SPOTIFY),
        )
        val combined = track("Song", "Artist", provider = ProviderId.SPOTIFY).let { spotify ->
            val youtube = track("Song", "Artist", provider = ProviderId.YOUTUBE_MUSIC, bitrate = 256)
            spotify.copy(sources = spotify.sources + youtube.sources)
        }

        session.playAwait(combined)

        assertEquals(ProviderId.YOUTUBE_MUSIC, session.nowPlaying.value.resolved?.source?.provider)
        assertNotNull(session.nowPlaying.value.fallback)
        assertEquals(ProviderId.SPOTIFY, session.nowPlaying.value.fallback?.from)
    }

    @Test
    fun playQueueIndexKeepsExistingQueue() = runTest {
        val engine = RecordingEngine { }
        val session = PlayerSession(
            engine = engine,
            resolver = DefaultSourceResolver(),
            scope = backgroundScope,
        )
        val first = track("One", "A", provider = ProviderId.SAMPLE)
        val second = track("Two", "B", provider = ProviderId.SAMPLE)
        session.play(listOf(first, second), startIndex = 0)
        val idsAfterPlay = session.queue.queue.value.items.map { it.id }
        session.playQueueIndex(1)

        assertEquals(idsAfterPlay, session.queue.queue.value.items.map { it.id })
        assertEquals(2, session.queue.queue.value.items.size)
        assertEquals("Two", session.queue.queue.value.current?.track?.title)
    }
}

private class RecordingEngine(
    private val onPlay: (PlaybackHandle) -> Unit,
) : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?) {
        onPlay(handle)
        state.value = EngineState(status = EngineStatus.PLAYING, durationMs = 1000)
    }

    override fun pause() {
        state.value = state.value.copy(status = EngineStatus.PAUSED)
    }

    override fun resume() {
        state.value = state.value.copy(status = EngineStatus.PLAYING)
    }

    override fun seekTo(positionMs: Long) {
        state.value = state.value.copy(positionMs = positionMs)
    }

    override fun stop() {
        state.value = EngineState()
    }

    override fun setVolume(volume: Float) = Unit
}

@Suppress("unused")
private fun unusedTrack(): Track = track("x", "y", provider = ProviderId.SAMPLE)
