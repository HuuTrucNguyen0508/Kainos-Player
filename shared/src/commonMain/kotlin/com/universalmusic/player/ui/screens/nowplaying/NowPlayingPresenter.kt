package com.universalmusic.player.ui.screens

import com.universalmusic.player.app.AppContainer
import com.universalmusic.player.data.cache.DownloadItemState
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.NowPlayingState
import com.universalmusic.player.domain.playback.SleepTimerState
import com.universalmusic.player.platform.requiresExplicitSpotifyDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal data class NowPlayingUiState(
    val now: NowPlayingState,
    val queue: PlaybackQueue,
    val sleepTimer: SleepTimerState,
    val availability: TrackAvailabilityInfo?,
    val qualityLabel: String?,
    val providerName: String?,
    val actualSourceLabel: String?,
    val detailLines: List<String>,
    val showSpotifyOutput: Boolean,
    val canCorrectMatch: Boolean,
    val repeat: RepeatDisplay,
    val shuffle: ShuffleDisplay,
    val sleepRemainingLabel: String?,
    val trackIdentity: String?,
)

internal class NowPlayingPresenter(
    val nowPlaying: StateFlow<NowPlayingState>,
    private val queue: StateFlow<PlaybackQueue>,
    val volume: StateFlow<Float>,
    val settings: StateFlow<AppSettings>,
    private val spotifyState: StateFlow<ProviderState>,
    private val sleepTimer: StateFlow<SleepTimerState>,
    private val downloads: StateFlow<Map<String, DownloadItemState>>,
    private val explicitSpotifyDevice: Boolean,
    private val availabilityFor: (Track, Map<String, DownloadItemState>) -> TrackAvailabilityInfo,
    private val togglePlayPauseAction: () -> Unit,
    private val skipPrevious: () -> Unit,
    private val skipNext: () -> Unit,
    private val seekToPosition: (Long) -> Unit,
    private val cycleRepeatAction: () -> Unit,
    private val toggleShuffleAction: () -> Unit,
    private val canSkipNextAction: () -> Boolean,
    private val toggleFavoriteTrack: (Track) -> Boolean,
    private val setFavoriteFlag: (Boolean) -> Unit,
    private val setVolumeLive: (Float) -> Unit,
    private val setPlaybackVolumeAction: (Float) -> Unit,
    private val retry: () -> Unit,
    private val tryAnother: () -> Unit,
    private val startSleepDurationAction: (Long) -> Unit,
    private val startSleepEndOfTrackAction: () -> Unit,
    private val cancelSleepAction: () -> Unit,
    private val scope: CoroutineScope,
) {
    // Position ticks stay on [nowPlaying]. The rest of the screen follows this snapshot.
    private val stableNow = nowPlaying
        .map { it.copy(positionMs = 0) }
        .distinctUntilChanged()

    val state: StateFlow<NowPlayingUiState> = combine(
        combine(stableNow, queue, sleepTimer) { now, playbackQueue, timer ->
            Triple(now, playbackQueue, timer)
        },
        combine(spotifyState, downloads) { spotify, downloadMap ->
            spotify to downloadMap
        },
    ) { playback, providers ->
        derive(playback.first, playback.second, playback.third, providers.first, providers.second)
    }.stateIn(scope, SharingStarted.Eagerly, snapshot())

    fun togglePlayPause() = togglePlayPauseAction()

    fun skipToPrevious() = skipPrevious()

    fun skipToNext() = skipNext()

    fun seekTo(positionMs: Long) = seekToPosition(positionMs)

    fun cycleRepeat() = cycleRepeatAction()

    fun toggleShuffle() = toggleShuffleAction()

    fun canSkipNext(): Boolean = canSkipNextAction()

    fun toggleFavorite(track: Track) {
        val favorite = toggleFavoriteTrack(track)
        setFavoriteFlag(favorite)
    }

    fun setVolume(volume: Float) = setVolumeLive(volume)

    fun setPlaybackVolume(volume: Float) = setPlaybackVolumeAction(volume)

    fun retryPlayback() = retry()

    fun tryAnotherSource() = tryAnother()

    fun startSleepDuration(durationMs: Long) = startSleepDurationAction(durationMs)

    fun startSleepEndOfTrack() = startSleepEndOfTrackAction()

    fun cancelSleepTimer() = cancelSleepAction()

    private fun snapshot(): NowPlayingUiState = derive(
        nowPlaying.value.copy(positionMs = 0),
        queue.value,
        sleepTimer.value,
        spotifyState.value,
        downloads.value,
    )

    private fun derive(
        now: NowPlayingState,
        playbackQueue: PlaybackQueue,
        timer: SleepTimerState,
        spotify: ProviderState,
        downloads: Map<String, DownloadItemState>,
    ): NowPlayingUiState {
        val track = now.track
        val provider = now.resolved?.source?.provider
        val quality = now.resolved?.source?.quality
        val availability = track?.let { availabilityFor(it, downloads) }
        return NowPlayingUiState(
            now = now,
            queue = playbackQueue,
            sleepTimer = timer,
            availability = availability,
            qualityLabel = nowPlayingQualityLabel(quality, track != null, now.resolved != null),
            providerName = displayedProviderName(provider?.displayName, track),
            actualSourceLabel = actualPlaybackSourceLabel(
                now.resolved?.source?.providerTrackId,
                provider,
                availability?.isSpotifyViaYouTubeMatch == true,
            ),
            detailLines = nowPlayingDetailLines(
                quality?.technicalDetail,
                now.resolved?.reason,
                now.fallback?.message,
            ),
            showSpotifyOutput = showSpotifyOutput(explicitSpotifyDevice, provider, track, spotify),
            canCorrectMatch = canCorrectYouTubeMatch(track, availability),
            repeat = repeatDisplay(playbackQueue.repeat),
            shuffle = shuffleDisplay(playbackQueue.shuffle),
            sleepRemainingLabel = sleepTimerRemainingLabel(timer),
            trackIdentity = now.queueItemId ?: track?.canonicalId,
        )
    }

    companion object {
        fun from(container: AppContainer, scope: CoroutineScope): NowPlayingPresenter = NowPlayingPresenter(
            nowPlaying = container.player.nowPlaying,
            queue = container.player.queue.queue,
            volume = container.player.volume,
            settings = container.settings,
            spotifyState = container.spotify.state,
            sleepTimer = container.sleepTimer.state,
            downloads = container.heartedAudio.downloads,
            explicitSpotifyDevice = requiresExplicitSpotifyDevice(),
            availabilityFor = { track, _ -> container.trackAvailability(track) },
            togglePlayPauseAction = container.player::togglePlayPause,
            skipPrevious = container.player::skipToPrevious,
            skipNext = container.player::skipToNext,
            seekToPosition = container.player::seekTo,
            cycleRepeatAction = container.player::cycleRepeat,
            toggleShuffleAction = container.player::toggleShuffle,
            canSkipNextAction = container.player::canSkipNext,
            toggleFavoriteTrack = container.library::toggleFavorite,
            setFavoriteFlag = container.player::setFavorite,
            setVolumeLive = container.player::setVolume,
            setPlaybackVolumeAction = container::setPlaybackVolume,
            retry = container.player::retryPlayback,
            tryAnother = container.player::tryAnotherSource,
            startSleepDurationAction = container.sleepTimer::startDuration,
            startSleepEndOfTrackAction = container.sleepTimer::startEndOfTrack,
            cancelSleepAction = container.sleepTimer::cancel,
            scope = scope,
        )
    }
}
