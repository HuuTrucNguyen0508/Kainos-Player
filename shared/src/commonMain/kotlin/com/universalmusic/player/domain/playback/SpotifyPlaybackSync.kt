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
    /** True after Spotify has reported paused for this local pause. */
    val pauseAcknowledged: Boolean = false,
    /** True when a later remote play should clear the local pause guard. */
    val clearUserPause: Boolean = false,
)

/** Values captured before a Spotify observe request, compared again after it returns. */
data class SpotifyObservationGate(
    val requestToken: Long,
    val transportGeneration: Long,
    val userPaused: Boolean,
)

/**
 * An observation captured before a local pause, resume, or seek must not be applied.
 * [current] is read after the network call.
 */
fun spotifyObservationStillValid(
    captured: SpotifyObservationGate,
    current: SpotifyObservationGate,
    spotifyActive: Boolean,
): Boolean = spotifyActive &&
    captured.requestToken == current.requestToken &&
    captured.transportGeneration == current.transportGeneration &&
    captured.userPaused == current.userPaused

private const val SEEK_DRIFT_MS = 2_000L
private const val COMPLETION_SLACK_MS = 1_500L
private const val MISSES_BEFORE_WARNING = 3

/**
 * Playing observations ignored after a local pause when the pause command has not
 * finished. The next observation can be a remote resume even if Spotify never
 * reported paused.
 */
const val SPOTIFY_PAUSE_GUARD_MAX_OBSERVATIONS = 2

/** The local pause is still protected from a playing observation. */
fun spotifyPauseGuardOpen(
    pauseCommandCompleted: Boolean,
    observationsSincePause: Int,
): Boolean = !pauseCommandCompleted && observationsSincePause < SPOTIFY_PAUSE_GUARD_MAX_OBSERVATIONS

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
    pauseAcknowledged: Boolean = false,
    pauseGuardOpen: Boolean = true,
): SpotifySyncDecision {
    if (userPaused) {
        return reconcileWhileUserPaused(
            status = status,
            positionMs = positionMs,
            expectedTrackId = expectedTrackId,
            misses = misses,
            observed = observed,
            pauseAcknowledged = pauseAcknowledged,
            pauseGuardOpen = pauseGuardOpen,
        )
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

private fun reconcileWhileUserPaused(
    status: EngineStatus,
    positionMs: Long,
    expectedTrackId: String?,
    misses: Int,
    observed: SpotifyObservedPlayback?,
    pauseAcknowledged: Boolean,
    pauseGuardOpen: Boolean,
): SpotifySyncDecision {
    if (observed != null && observed.isPlaying && tracksMatch(observed, expectedTrackId) &&
        (pauseAcknowledged || !pauseGuardOpen)
    ) {
        return SpotifySyncDecision(
            status = EngineStatus.PLAYING,
            positionMs = observed.progressMs?.coerceAtLeast(0) ?: positionMs,
            syncWarning = null,
            misses = 0,
            snapClock = true,
            pauseAcknowledged = false,
            clearUserPause = true,
        )
    }
    if (!pauseAcknowledged) {
        val paused = observed?.takeIf { !it.isPlaying && tracksMatch(it, expectedTrackId) }
        return SpotifySyncDecision(
            status = status,
            positionMs = paused?.progressMs?.coerceAtLeast(0) ?: positionMs,
            syncWarning = null,
            misses = misses,
            snapClock = paused != null,
            pauseAcknowledged = paused != null,
        )
    }
    if (observed == null) {
        val nextMisses = misses + 1
        val warning = if (nextMisses >= MISSES_BEFORE_WARNING) "Playback state unavailable" else null
        return SpotifySyncDecision(
            status = status,
            positionMs = positionMs,
            syncWarning = warning,
            misses = nextMisses,
            snapClock = false,
            pauseAcknowledged = true,
        )
    }
    if (!tracksMatch(observed, expectedTrackId)) {
        return SpotifySyncDecision(
            status = status,
            positionMs = positionMs,
            syncWarning = "Spotify is playing a different track",
            misses = 0,
            snapClock = false,
            pauseAcknowledged = true,
        )
    }
    return SpotifySyncDecision(
        status = status,
        positionMs = observed.progressMs?.coerceAtLeast(0) ?: positionMs,
        syncWarning = null,
        misses = 0,
        snapClock = true,
        pauseAcknowledged = true,
    )
}

private fun tracksMatch(observed: SpotifyObservedPlayback, expectedTrackId: String?): Boolean {
    val observedId = observed.trackId ?: return true
    val expected = expectedTrackId ?: return true
    return observedId == expected
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
