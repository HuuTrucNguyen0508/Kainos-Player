package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.cache.PlaybackSourceKind
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.QualityConfidence
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.ResolvedPlayback
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.NowPlayingState
import com.universalmusic.player.domain.playback.SleepTimerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NowPlayingContentTest {
    @Test
    fun qualityLabelsForSpotifyFlacAndUnknown() {
        val spotify = AudioQuality(
            tier = QualityTier.STANDARD,
            sampleRateHz = 44_100,
            bitDepth = 16,
            confidence = QualityConfidence.UNKNOWN,
        )
        assertEquals("Unknown", nowPlayingQualityLabel(spotify, hasTrack = true, hasResolved = true))
        assertTrue(spotify.technicalDetail!!.contains("16-bit"))
        assertTrue(spotify.technicalDetail!!.contains("44.1 kHz"))

        val flac = AudioQuality(
            tier = QualityTier.LOSSLESS,
            codec = "flac",
            sampleRateHz = 96_000,
            bitDepth = 24,
        )
        assertEquals("Lossless · FLAC · 24-bit · 96 kHz", nowPlayingQualityLabel(flac, hasTrack = true, hasResolved = true))

        val unknown = AudioQuality(tier = QualityTier.HIGH, confidence = QualityConfidence.UNKNOWN)
        assertEquals("Unknown", nowPlayingQualityLabel(unknown, hasTrack = true, hasResolved = true))
        assertEquals("quality checked on play", nowPlayingQualityLabel(null, hasTrack = true, hasResolved = false))
        assertNull(nowPlayingQualityLabel(null, hasTrack = false, hasResolved = false))
    }

    @Test
    fun shuffleAndRepeatDisplay() {
        val off = repeatDisplay(RepeatMode.OFF)
        assertFalse(off.active)
        assertFalse(off.one)
        assertEquals("Repeat: off", off.tooltip)
        assertEquals("Repeat off. Change repeat mode", off.contentDescription)

        val all = repeatDisplay(RepeatMode.ALL)
        assertTrue(all.active)
        assertFalse(all.one)
        assertEquals("Repeat: all", all.tooltip)

        val one = repeatDisplay(RepeatMode.ONE)
        assertTrue(one.active)
        assertTrue(one.one)
        assertEquals("Repeat one. Change repeat mode", one.contentDescription)

        val on = shuffleDisplay(true)
        assertTrue(on.on)
        assertEquals("Shuffle on", on.tooltip)
        assertEquals("Turn shuffle off", on.contentDescription)
        val quiet = shuffleDisplay(false)
        assertFalse(quiet.on)
        assertEquals("Shuffle off", quiet.tooltip)
        assertEquals("Turn shuffle on", quiet.contentDescription)
    }

    @Test
    fun progressAndClockFormatting() {
        assertEquals("0:00", formatTime(0))
        assertEquals("1:05", formatTime(65_000))
        assertEquals("0:00", formatTime(-50))
        assertEquals(0f, playbackProgress(1_000, null))
        assertEquals(0.5f, playbackProgress(30_000, 60_000))
        assertEquals(1f, playbackProgress(90_000, 60_000))
        assertEquals(15_000L, displayedPositionMs(0.25f, 60_000, positionMs = 1))
        assertEquals(1L, displayedPositionMs(null, 60_000, positionMs = 1))
        assertEquals(90_000L, knownDurationMs(null, 90_000))
        assertNull(knownDurationMs(0, 0))
        assertEquals(0.46f, volumeAfterScroll(0.5f, scrollY = 1f)!!, absoluteTolerance = 0.0001f)
        assertNull(volumeAfterScroll(0.5f, scrollY = 0f))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NowPlayingPresenterTest {
    @Test
    fun qualityLabelUpdatesWithoutRebuildingOnPositionTicks() = runTest(UnconfinedTestDispatcher()) {
        val song = track("Song", "Artist", provider = ProviderId.SPOTIFY, canonicalId = "spotify:song")
        val now = MutableStateFlow(NowPlayingState(track = song, positionMs = 1_000))
        val presenter = nowPlayingPresenter(nowPlaying = now)
        assertEquals("quality checked on play", presenter.state.value.qualityLabel)
        assertEquals("Spotify", presenter.state.value.providerName)

        val settled = presenter.state.value
        now.value = now.value.copy(positionMs = 20_000)
        assertEquals(settled, presenter.state.value)
        assertEquals(20_000, presenter.nowPlaying.value.positionMs)

        val flac = AudioQuality(
            tier = QualityTier.LOSSLESS,
            codec = "flac",
            sampleRateHz = 96_000,
            bitDepth = 24,
        )
        now.value = now.value.copy(
            resolved = ResolvedPlayback(
                track = song,
                source = PlaybackSource(
                    provider = ProviderId.LOCAL,
                    providerTrackId = "local-song",
                    quality = flac,
                    isPlayable = true,
                    handle = PlaybackHandle.Url("file:///tmp/song.flac"),
                ),
                fallbacks = emptyList(),
                reason = "local file",
            ),
        )
        assertEquals("Lossless · FLAC · 24-bit · 96 kHz", presenter.state.value.qualityLabel)
        assertEquals("Playing from local file", presenter.state.value.actualSourceLabel)
        assertEquals(RepeatMode.OFF, presenter.state.value.queue.repeat)
        assertFalse(presenter.state.value.shuffle.on)
    }

    @Test
    fun togglePlayPauseCallsTheTransportLambda() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val presenter = nowPlayingPresenter(togglePlayPauseAction = { calls += 1 })
        presenter.togglePlayPause()
        assertEquals(1, calls)
    }

    private fun TestScope.nowPlayingPresenter(
        nowPlaying: StateFlow<NowPlayingState> = MutableStateFlow(NowPlayingState()),
        queue: StateFlow<PlaybackQueue> = MutableStateFlow(PlaybackQueue()),
        volume: StateFlow<Float> = MutableStateFlow(1f),
        settings: StateFlow<AppSettings> = MutableStateFlow(AppSettings()),
        spotifyState: StateFlow<ProviderState> = MutableStateFlow(ProviderState.AVAILABLE),
        sleepTimer: StateFlow<SleepTimerState> = MutableStateFlow(SleepTimerState()),
        downloads: StateFlow<Map<String, DownloadItemState>> = MutableStateFlow(emptyMap()),
        togglePlayPauseAction: () -> Unit = {},
        scope: CoroutineScope = backgroundScope,
    ) = NowPlayingPresenter(
        nowPlaying = nowPlaying,
        queue = queue,
        volume = volume,
        settings = settings,
        spotifyState = spotifyState,
        sleepTimer = sleepTimer,
        downloads = downloads,
        explicitSpotifyDevice = false,
        availabilityFor = { _, _ ->
            TrackAvailabilityInfo(
                status = TrackAvailability.UNAVAILABLE,
                label = "Unavailable",
                playbackSource = PlaybackSourceKind.UNKNOWN,
                playbackSourceLabel = "Unknown",
            )
        },
        togglePlayPauseAction = togglePlayPauseAction,
        skipPrevious = {},
        skipNext = {},
        seekToPosition = {},
        cycleRepeatAction = {},
        toggleShuffleAction = {},
        canSkipNextAction = { false },
        toggleFavoriteTrack = { false },
        setFavoriteFlag = {},
        setVolumeLive = {},
        setPlaybackVolumeAction = {},
        retry = {},
        tryAnother = {},
        startSleepDurationAction = {},
        startSleepEndOfTrackAction = {},
        cancelSleepAction = {},
        scope = scope,
    )
}
