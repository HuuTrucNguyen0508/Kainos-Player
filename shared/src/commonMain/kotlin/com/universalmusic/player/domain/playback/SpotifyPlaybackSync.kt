package com.universalmusic.player.domain.playback

data class SpotifyObservedPlayback(
    val isPlaying: Boolean,
    val progressMs: Long?,
    val trackId: String?,
)

data class SpotifySyncDecision(
    val status: EngineStatus,
    val positionMs: Long,
    val syncWarning: String?,
    val misses: Int,
    /** True when the local clock should restart from [positionMs]. */
    val snapClock: Boolean,
)

private const val SEEK_DRIFT_MS = 2_000L
private const val COMPLETION_SLACK_MS = 1_500L
private const val MISSES_BEFORE_WARNING = 3

/**
 * Fold one Spotify Connect observation into the local engine status.
 * A missing observation does not invent progress; repeated misses surface
 * "Playback state unavailable".
 */
fun reconcileSpotifyObservation(
    status: EngineStatus,
    positionMs: Long,
    durationMs: Long?,
    expectedTrackId: String?,
    userPaused: Boolean,
    misses: Int,
    observed: SpotifyObservedPlayback?,
): SpotifySyncDecision {
    if (userPaused) {
        return SpotifySyncDecision(status, positionMs, syncWarning = null, misses = misses, snapClock = false)
    }
    if (observed == null) {
        val nextMisses = misses + 1
        val warning = if (nextMisses >= MISSES_BEFORE_WARNING) "Playback state unavailable" else null
        return SpotifySyncDecision(status, positionMs, warning, nextMisses, snapClock = false)
    }
    if (observed.trackId != null && expectedTrackId != null && observed.trackId != expectedTrackId) {
        return SpotifySyncDecision(
            status = status,
            positionMs = positionMs,
            syncWarning = "Spotify is playing a different track",
            misses = 0,
            snapClock = false,
        )
    }
    val progress = observed.progressMs?.coerceAtLeast(0) ?: positionMs
    val nearEnd = durationMs != null && progress >= (durationMs - COMPLETION_SLACK_MS).coerceAtLeast(0)
    if (!observed.isPlaying && nearEnd) {
        return SpotifySyncDecision(
            status = EngineStatus.ENDED,
            positionMs = durationMs,
            syncWarning = null,
            misses = 0,
            snapClock = true,
        )
    }
    if (!observed.isPlaying && status == EngineStatus.PLAYING) {
        return SpotifySyncDecision(
            status = EngineStatus.PAUSED,
            positionMs = progress,
            syncWarning = null,
            misses = 0,
            snapClock = true,
        )
    }
    if (observed.isPlaying && (status == EngineStatus.PAUSED || status == EngineStatus.BUFFERING)) {
        return SpotifySyncDecision(
            status = EngineStatus.PLAYING,
            positionMs = progress,
            syncWarning = null,
            misses = 0,
            snapClock = true,
        )
    }
    val drift = kotlin.math.abs(progress - positionMs)
    if (observed.isPlaying && drift > SEEK_DRIFT_MS) {
        return SpotifySyncDecision(
            status = EngineStatus.PLAYING,
            positionMs = progress,
            syncWarning = null,
            misses = 0,
            snapClock = true,
        )
    }
    return SpotifySyncDecision(
        status = if (observed.isPlaying) EngineStatus.PLAYING else status,
        positionMs = positionMs,
        syncWarning = null,
        misses = 0,
        snapClock = false,
    )
}

fun friendlyPlaybackMessage(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return "Playback failed."
    val lower = text.lowercase()
    return when {
        "429" in text || "rate limit" in lower || "too many requests" in lower ->
            "Spotify is limiting requests right now. Wait a moment, then retry."
        "timeout" in lower || "unable to resolve" in lower || "network" in lower ||
            "connection reset" in lower || "failed to connect" in lower ->
            "The connection failed. Check the network, then retry."
        "premium" in lower ->
            "Spotify Premium is required for in-app playback."
        "no playable" in lower || "not available" in lower ->
            "This source cannot play the track."
        else -> text
    }
}
