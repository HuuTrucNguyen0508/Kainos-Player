package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.cache.HEARTED_AUDIO_CACHE_PROVIDER_PREFIX
import com.universalmusic.player.data.cache.PlaybackSourceKind
import com.universalmusic.player.data.cache.TrackAvailability
import com.universalmusic.player.data.cache.TrackAvailabilityInfo
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.SleepTimerMode
import com.universalmusic.player.domain.playback.SleepTimerState
import com.universalmusic.player.domain.playback.formatSleepTimerRemaining

internal fun knownDurationMs(reportedDurationMs: Long?, trackDurationMs: Long?): Long? =
    reportedDurationMs?.takeIf { it > 0 } ?: trackDurationMs?.takeIf { it > 0 }

internal fun playbackProgress(positionMs: Long, knownDurationMs: Long?): Float =
    if (knownDurationMs != null) {
        (positionMs.toFloat() / knownDurationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

internal fun displayedPositionMs(scrubFraction: Float?, knownDurationMs: Long?, positionMs: Long): Long =
    scrubFraction?.let { (it * (knownDurationMs ?: 0)).toLong() } ?: positionMs

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val minutes = total / 60
    val seconds = total % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}

/**
 * Badge shown under the title. Spotify's Web API does not report source format, so that
 * quality stays [AudioQuality.label] "Unknown" while the details line keeps 16-bit / 44.1 kHz
 * as a typical decode note. Unresolved tracks say quality is checked on play.
 */
internal fun nowPlayingQualityLabel(
    quality: AudioQuality?,
    hasTrack: Boolean,
    hasResolved: Boolean,
): String? = quality?.label ?: if (hasTrack && !hasResolved) "quality checked on play" else null

internal fun displayedProviderName(resolvedProviderName: String?, track: Track?): String? =
    resolvedProviderName ?: track?.sources?.firstOrNull()?.provider?.displayName

internal fun actualPlaybackSourceLabel(
    providerTrackId: String?,
    provider: ProviderId?,
    spotifyViaYouTubeMatch: Boolean,
): String? = when {
    providerTrackId?.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX) == true -> {
        if (spotifyViaYouTubeMatch) {
            "Playing from YouTube match cache"
        } else {
            "Playing from YouTube cache"
        }
    }
    provider == ProviderId.LOCAL -> "Playing from local file"
    provider == ProviderId.SPOTIFY -> "Playing from Spotify stream"
    provider == ProviderId.YOUTUBE_MUSIC -> "Playing from YouTube stream"
    provider != null -> "Playing from ${provider.displayName}"
    else -> null
}

internal fun nowPlayingDetailLines(
    technicalDetail: String?,
    resolveReason: String?,
    fallbackMessage: String?,
): List<String> = buildList {
    technicalDetail?.let(::add)
    resolveReason?.takeIf { it.isNotBlank() }?.let(::add)
    fallbackMessage?.let(::add)
}

internal fun showSpotifyOutput(
    explicitDevice: Boolean,
    provider: ProviderId?,
    track: Track?,
    spotifyState: ProviderState,
): Boolean = explicitDevice &&
    (provider == ProviderId.SPOTIFY || track?.sourceFor(ProviderId.SPOTIFY) != null) &&
    (spotifyState == ProviderState.AVAILABLE || spotifyState == ProviderState.RATE_LIMITED)

internal fun canCorrectYouTubeMatch(track: Track?, availability: TrackAvailabilityInfo?): Boolean =
    track != null && (
        availability?.isSpotifyViaYouTubeMatch == true ||
            availability?.playbackSource == PlaybackSourceKind.SPOTIFY_VIA_YOUTUBE_CACHE ||
            (
                track.canonicalId.startsWith("spotify:") &&
                    availability?.status == TrackAvailability.CACHED
                )
        )

internal fun visibleAvailability(info: TrackAvailabilityInfo?): TrackAvailabilityInfo? =
    info?.takeIf {
        it.status == TrackAvailability.CACHED ||
            it.status == TrackAvailability.DOWNLOADING ||
            it.status == TrackAvailability.FAILED ||
            it.status == TrackAvailability.UNAVAILABLE
    }

internal fun availabilityLine(info: TrackAvailabilityInfo): String = buildString {
    append(info.label)
    if (info.isSpotifyViaYouTubeMatch) append(" · Spotify identity kept")
}

internal data class RepeatDisplay(
    val one: Boolean,
    val active: Boolean,
    val tooltip: String,
    val contentDescription: String,
)

internal fun repeatDisplay(mode: RepeatMode): RepeatDisplay = RepeatDisplay(
    one = mode == RepeatMode.ONE,
    active = mode != RepeatMode.OFF,
    tooltip = "Repeat: ${mode.name.lowercase()}",
    contentDescription = "Repeat ${mode.name.lowercase()}. Change repeat mode",
)

internal data class ShuffleDisplay(
    val on: Boolean,
    val tooltip: String,
    val contentDescription: String,
)

internal fun shuffleDisplay(on: Boolean): ShuffleDisplay = ShuffleDisplay(
    on = on,
    tooltip = if (on) "Shuffle on" else "Shuffle off",
    contentDescription = if (on) "Turn shuffle off" else "Turn shuffle on",
)

internal fun sleepTimerRemainingLabel(state: SleepTimerState): String? {
    if (!state.active) return null
    return when (val mode = state.mode) {
        is SleepTimerMode.Duration ->
            state.remainingMs?.let { "Sleep · ${formatSleepTimerRemaining(it)}" } ?: "Sleep timer"
        is SleepTimerMode.EndOfTrack -> "Sleep · end of track"
        null -> "Sleep timer"
    }
}

internal fun volumeAfterScroll(current: Float, scrollY: Float): Float? {
    if (scrollY == 0f) return null
    val step = (-scrollY * 0.04f).coerceIn(-0.2f, 0.2f)
    return (current + step).coerceIn(0f, 1f)
}

internal fun volumePercentLabel(volume: Float): String = "${(volume * 100).toInt()}%"

internal enum class VolumeIconKind {
    MUTE,
    DOWN,
    UP,
}

internal fun volumeIconKind(volume: Float): VolumeIconKind = when {
    volume <= 0.001f -> VolumeIconKind.MUTE
    volume < 0.5f -> VolumeIconKind.DOWN
    else -> VolumeIconKind.UP
}
