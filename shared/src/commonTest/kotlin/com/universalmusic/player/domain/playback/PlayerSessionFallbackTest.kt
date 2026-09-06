package com.universalmusic.player.domain.playback

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackPreferences
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.ResolvedPlayback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSessionFallbackTest {
    @Test
    fun pauseWhileResolvingCancelsPendingPlayback() = runTest {
        var resolutions = 0
        val resolver = object : SourceResolver {
            override suspend fun resolve(track: Track, preferences: PlaybackPreferences): ResolvedPlayback {
                resolutions++
                awaitCancellation()
            }
        }
        val session = PlayerSession(RecordingEngine {}, resolver, backgroundScope)
        session.play(track("Loading", "Artist", provider = ProviderId.SAMPLE))
        runCurrent()
        session.togglePlayPause()
        runCurrent()

        assertEquals(1, resolutions)
        assertEquals(false, session.nowPlaying.value.buffering)
        assertEquals(false, session.nowPlaying.value.isPlaying)
    }

    @Test
    fun selectingUnplayableTrackStopsPreviousAudio() = runTest {
        val engine = RecordingEngine {}
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.playAwait(track("Old", "Artist", provider = ProviderId.SAMPLE))
        assertEquals(EngineStatus.PLAYING, engine.state.value.status)

        session.play(track("Unavailable", "Artist", provider = ProviderId.SAMPLE).copy(sources = emptyList()))
        runCurrent()

        assertEquals(EngineStatus.IDLE, engine.state.value.status)
        assertNotNull(session.nowPlaying.value.error)
        assertEquals(null, session.nowPlaying.value.resolved)
    }

    @Test
    fun replacingLoadingTrackDoesNotStartItsFallback() = runTest {
        val attempts = mutableListOf<PlaybackHandle>()
        val old = track("Old", "Artist", provider = ProviderId.SPOTIFY).let {
            it.copy(sources = it.sources + track("Old", "Artist", provider = ProviderId.YOUTUBE_MUSIC).sources)
        }
        val latest = track("Latest", "Artist", provider = ProviderId.SAMPLE)
        val engine = object : PlaybackEngine by RecordingEngine({}) {
            override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?) {
                attempts += handle
                if (handle == old.sources.first().handle) awaitCancellation()
            }
        }
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope,
            initialPreferences = PlaybackPreferences(preferredProvider = ProviderId.SPOTIFY))
        session.play(old)
        runCurrent()
        session.play(latest)
        runCurrent()

        assertEquals(listOf(old.sources.first().handle, latest.sources.single().handle), attempts)
        assertEquals(latest.canonicalId, session.nowPlaying.value.track?.canonicalId)
    }

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

    @Test
    fun engineFailureKeepsConcreteErrorWhenNoFallbackExists() = runTest {
        val engine = RecordingEngine {}
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.playAwait(track("Song", "Artist", provider = ProviderId.SPOTIFY))
        assertEquals(EngineStatus.PLAYING, engine.state.value.status)

        engine.state.value = EngineState(
            status = EngineStatus.FAILED,
            error = "Spotify Connect playback failed (404): No active device found",
        )
        runCurrent()

        assertEquals(
            "Spotify Connect playback failed (404): No active device found",
            session.nowPlaying.value.error,
        )
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
