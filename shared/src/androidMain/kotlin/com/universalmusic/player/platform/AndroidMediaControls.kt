package com.universalmusic.player.platform

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.NowPlayingState
import com.universalmusic.player.domain.playback.PlayerSession
import com.universalmusic.player.domain.model.PlaybackQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Bridges [PlayerSession] to Android Media3 session / notification / Bluetooth controls.
 * Session remains the owner of queue and skip semantics.
 * All ExoPlayer access runs on the application main looper.
 */
object AndroidMediaControls {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var session: PlayerSession? = null

    @Volatile
    private var syncJob: Job? = null

    @Volatile
    var forwardingPlayer: KainosForwardingPlayer? = null
        private set

    fun bind(session: PlayerSession, scope: CoroutineScope) {
        this.session = session
        syncJob?.cancel()
        syncJob = scope.launch {
            // Metadata / transport: skip HyperOS island rebuilds on position ticks.
            launch {
                combine(session.nowPlaying, session.queue.queue) { now, queue -> now to queue }
                    .distinctUntilChangedBy { (now, queue) -> sessionIdentity(now, queue) }
                    .collectLatest { (now, queue) -> publish(now, queue) }
            }
            // Position-only: update overlay clock without replacing MediaItem.
            launch {
                session.nowPlaying
                    .map { PositionTick(it.positionMs, it.durationMs) }
                    .distinctUntilChanged()
                    .collectLatest { tick ->
                        onMain {
                            forwardingPlayer?.updateOverlayPosition(tick.positionMs, tick.durationMs)
                        }
                    }
            }
        }
    }

    fun unbind() {
        syncJob?.cancel()
        syncJob = null
        session = null
    }

    fun attachPlayer(player: KainosForwardingPlayer?) {
        onMain {
            forwardingPlayer = player
            if (player != null) {
                session?.let { publishOnMain(it.nowPlaying.value, it.queue.queue.value) }
            }
        }
    }

    fun currentTrack(): Track? = session?.nowPlaying?.value?.track

    fun currentDurationMs(): Long? = session?.nowPlaying?.value?.durationMs

    fun skipToNext() = session?.skipToNext()

    fun skipToPrevious() = session?.skipToPrevious()

    fun togglePlayPause() = session?.togglePlayPause()

    /** Idempotent play: resume or restart; no-op if already playing. */
    fun play() = session?.playTransport()

    /** Idempotent pause: also cancels buffering resolution. */
    fun pause() = session?.pauseTransport()

    fun seekTo(positionMs: Long) = session?.seekTo(positionMs)

    fun canSkipNext(): Boolean = session?.canSkipNext() == true

    fun canSkipPrevious(): Boolean {
        val q = session?.queue?.queue?.value ?: return false
        return q.items.size > 1 || (session?.nowPlaying?.value?.positionMs ?: 0L) > 3_000L
    }

    fun toggleShuffle() = session?.toggleShuffle()

    fun cycleRepeat() = session?.cycleRepeat()

    fun isShuffleEnabled(): Boolean = session?.queue?.queue?.value?.shuffle == true

    fun repeatMode(): RepeatMode = session?.queue?.queue?.value?.repeat ?: RepeatMode.OFF

    private fun publish(now: NowPlayingState, queue: PlaybackQueue) {
        onMain { publishOnMain(now, queue) }
    }

    private fun publishOnMain(now: NowPlayingState, queue: PlaybackQueue) {
        val player = forwardingPlayer ?: return
        val track = now.track
        val metadata = track?.toMediaMetadata(now.durationMs) ?: MediaMetadata.Builder()
            .setTitle("Kainos Player")
            .setIsPlayable(true)
            .build()
        val mediaId = track?.canonicalId ?: queue.current?.id ?: "kainos-idle"
        val playingSpotify = now.resolved?.source?.provider == ProviderId.SPOTIFY
        player.publishSessionState(
            mediaId = mediaId,
            metadata = metadata,
            artworkUri = track?.artwork?.url?.let(Uri::parse),
            isPlaying = now.isPlaying,
            buffering = now.buffering,
            positionMs = now.positionMs,
            durationMs = now.durationMs,
            canSeek = track != null,
            canSkipNext = canSkipNext(),
            canSkipPrevious = canSkipPrevious(),
            spotifyActive = playingSpotify,
        )
    }

    private fun sessionIdentity(now: NowPlayingState, queue: PlaybackQueue): SessionIdentity {
        val track = now.track
        return SessionIdentity(
            mediaId = track?.canonicalId ?: queue.current?.id ?: "kainos-idle",
            title = track?.title,
            artist = track?.artistLine,
            album = track?.album?.title,
            artwork = track?.artwork?.url,
            isPlaying = now.isPlaying,
            buffering = now.buffering,
            durationMs = now.durationMs,
            canSeek = track != null,
            canSkipNext = canSkipNext(),
            canSkipPrevious = queue.items.size > 1 || now.positionMs > 3_000L,
            spotifyActive = now.resolved?.source?.provider == ProviderId.SPOTIFY,
        )
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private data class PositionTick(val positionMs: Long, val durationMs: Long?)

    private data class SessionIdentity(
        val mediaId: String,
        val title: String?,
        val artist: String?,
        val album: String?,
        val artwork: String?,
        val isPlaying: Boolean,
        val buffering: Boolean,
        val durationMs: Long?,
        val canSeek: Boolean,
        val canSkipNext: Boolean,
        val canSkipPrevious: Boolean,
        val spotifyActive: Boolean,
    )
}

internal fun Track.toMediaMetadata(durationMs: Long? = this.durationMs): MediaMetadata {
    val builder = MediaMetadata.Builder()
        .setTitle(title)
        .setArtist(artistLine.ifBlank { "Unknown artist" })
        .setAlbumTitle(album?.title)
        .setIsBrowsable(false)
        .setIsPlayable(true)
    durationMs?.takeIf { it > 0 }?.let { builder.setDurationMs(it) }
    artwork?.url?.let { builder.setArtworkUri(Uri.parse(it)) }
    return builder.build()
}

internal fun mediaItemForUrl(url: String, track: Track?, durationMs: Long?): MediaItem {
    val metadata = track?.toMediaMetadata(durationMs) ?: MediaMetadata.Builder()
        .setTitle("Kainos Player")
        .setIsPlayable(true)
        .build()
    return MediaItem.Builder()
        .setUri(url)
        .setMediaId(track?.canonicalId ?: url)
        .setMediaMetadata(metadata)
        .build()
}

internal fun mediaItemForMetadata(track: Track?, durationMs: Long?, placeholderUri: Uri): MediaItem {
    val metadata = track?.toMediaMetadata(durationMs) ?: MediaMetadata.Builder()
        .setTitle("Kainos Player")
        .setIsPlayable(true)
        .build()
    return MediaItem.Builder()
        .setUri(placeholderUri)
        .setMediaId(track?.canonicalId ?: "kainos-spotify")
        .setMediaMetadata(metadata)
        .build()
}
