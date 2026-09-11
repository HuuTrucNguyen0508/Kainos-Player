package com.universalmusic.player.platform

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ExoPlayer wrapper that:
 * - Routes next/previous/seek (when Spotify overlay is active) to [AndroidMediaControls] / PlayerSession
 * - Keeps notification/island metadata in sync for both local ExoPlayer and Spotify backends
 * - Emits [Player.Listener] events when overlay playback state changes (ExoPlayer stays paused on silence)
 */
class KainosForwardingPlayer(
    private val exo: ExoPlayer,
) : ForwardingPlayer(exo) {
    private val listeners = CopyOnWriteArrayList<Player.Listener>()

    @Volatile private var spotifyActive = false
    @Volatile private var overlayPlaying = false
    @Volatile private var overlayBuffering = false
    @Volatile private var overlayPositionMs = 0L
    @Volatile private var overlayDurationMs = C.TIME_UNSET
    @Volatile private var canSkipNext = false
    @Volatile private var canSkipPrevious = false
    @Volatile private var canSeek = true
    @Volatile private var lastMediaId: String? = null

    private val exoForwarder = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (spotifyActive) return
            val self = this@KainosForwardingPlayer
            listeners.forEach { it.onEvents(self, events) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (spotifyActive) return
            listeners.forEach { it.onIsPlayingChanged(isPlaying) }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (spotifyActive) return
            listeners.forEach { it.onPlaybackStateChanged(playbackState) }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (spotifyActive) return
            listeners.forEach { it.onPlayWhenReadyChanged(playWhenReady, reason) }
        }

        override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
            if (spotifyActive) return
            listeners.forEach { it.onAvailableCommandsChanged(getAvailableCommands()) }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (spotifyActive) return
            listeners.forEach { it.onMediaItemTransition(mediaItem, reason) }
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            if (spotifyActive) return
            listeners.forEach { it.onMediaMetadataChanged(mediaMetadata) }
        }
    }

    init {
        exo.addListener(exoForwarder)
    }

    override fun addListener(listener: Player.Listener) {
        listeners.addIfAbsent(listener)
    }

    override fun removeListener(listener: Player.Listener) {
        listeners.remove(listener)
    }

    fun publishSessionState(
        mediaId: String,
        metadata: MediaMetadata,
        artworkUri: Uri?,
        isPlaying: Boolean,
        buffering: Boolean,
        positionMs: Long,
        durationMs: Long?,
        canSeek: Boolean,
        canSkipNext: Boolean,
        canSkipPrevious: Boolean,
        spotifyActive: Boolean,
    ) {
        val prevPlaying = overlayPlaying
        val prevBuffering = overlayBuffering
        val prevCommands = getAvailableCommands()
        val prevDuration = overlayDurationMs
        val prevPosition = overlayPositionMs

        this.spotifyActive = spotifyActive
        this.overlayPlaying = isPlaying
        this.overlayBuffering = buffering
        this.overlayPositionMs = positionMs.coerceAtLeast(0L)
        this.overlayDurationMs = durationMs?.takeIf { it > 0 } ?: C.TIME_UNSET
        this.canSeek = canSeek
        this.canSkipNext = canSkipNext
        this.canSkipPrevious = canSkipPrevious

        // Always pin artwork from session so a missing-art track cannot keep the previous cover.
        val meta = metadata.buildUpon().setArtworkUri(artworkUri).build()

        if (spotifyActive) {
            val placeholder = AndroidPlaybackService.silenceUri()
            val item = MediaItem.Builder()
                .setUri(placeholder)
                .setMediaId(mediaId)
                .setMediaMetadata(meta)
                .build()
            val current = exo.currentMediaItem
            val sameSilenceUri = current?.localConfiguration?.uri == placeholder
            when {
                sameSilenceUri -> {
                    val idChanged = current!!.mediaId != mediaId
                    val metaChanged = current.mediaMetadata != meta
                    if (idChanged || metaChanged) {
                        // Same silence URI: replace in place so HyperOS keeps the island.
                        exo.replaceMediaItem(0, item)
                        lastMediaId = mediaId
                        if (idChanged) {
                            listeners.forEach {
                                it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                            }
                        }
                        if (metaChanged) {
                            listeners.forEach { it.onMediaMetadataChanged(meta) }
                        }
                    }
                }
                current?.mediaId != mediaId || lastMediaId != mediaId -> {
                    exo.setMediaItem(item)
                    exo.prepare()
                    exo.playWhenReady = false
                    lastMediaId = mediaId
                    listeners.forEach {
                        it.onMediaItemTransition(item, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
                    }
                    listeners.forEach { it.onMediaMetadataChanged(meta) }
                }
                current != null && current.mediaMetadata != meta -> {
                    exo.replaceMediaItem(0, item)
                    listeners.forEach { it.onMediaMetadataChanged(meta) }
                }
            }
        } else if (exo.currentMediaItem != null) {
            val current = exo.currentMediaItem!!
            if (current.mediaMetadata != meta || current.mediaId != mediaId) {
                val updated = current.buildUpon()
                    .setMediaId(mediaId)
                    .setMediaMetadata(meta)
                    .build()
                exo.replaceMediaItem(exo.currentMediaItemIndex.coerceAtLeast(0), updated)
                lastMediaId = mediaId
            }
        } else if (!spotifyActive && mediaId == "kainos-idle") {
            clearRetainedMedia(notify = false)
        }

        // Always notify command / overlay changes so notification Next stays in sync for local/YT.
        notifyOverlayChanges(
            prevPlaying = prevPlaying,
            prevBuffering = prevBuffering,
            prevCommands = prevCommands,
            prevDuration = prevDuration,
            prevPosition = prevPosition,
        )
    }

    /** Position ticker only: no MediaItem swap. */
    fun updateOverlayPosition(positionMs: Long, durationMs: Long?) {
        overlayPositionMs = positionMs.coerceAtLeast(0L)
        overlayDurationMs = durationMs?.takeIf { it > 0 } ?: overlayDurationMs
    }

    fun setSpotifyActive(active: Boolean) {
        val prevPlaying = overlayPlaying
        val prevBuffering = overlayBuffering
        val prevCommands = getAvailableCommands()
        val prevDuration = overlayDurationMs
        val prevPosition = overlayPositionMs
        spotifyActive = active
        if (active) {
            // Engine path (instrumented tests / Spotify start) activates overlay as playing.
            overlayPlaying = true
            overlayBuffering = false
        } else {
            overlayPlaying = false
            overlayBuffering = false
        }
        notifyOverlayChanges(prevPlaying, prevBuffering, prevCommands, prevDuration, prevPosition)
    }

    fun clearRetainedMedia(notify: Boolean = true) {
        val prevPlaying = overlayPlaying
        val prevBuffering = overlayBuffering
        val prevCommands = getAvailableCommands()
        val prevDuration = overlayDurationMs
        val prevPosition = overlayPositionMs
        spotifyActive = false
        overlayPlaying = false
        overlayBuffering = false
        overlayPositionMs = 0L
        overlayDurationMs = C.TIME_UNSET
        lastMediaId = null
        canSkipNext = false
        canSkipPrevious = false
        canSeek = false
        exo.stop()
        exo.clearMediaItems()
        if (notify) {
            listeners.forEach { it.onMediaItemTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) }
            notifyOverlayChanges(prevPlaying, prevBuffering, prevCommands, prevDuration, prevPosition)
        }
    }

    private fun notifyOverlayChanges(
        prevPlaying: Boolean,
        prevBuffering: Boolean,
        prevCommands: Player.Commands,
        @Suppress("UNUSED_PARAMETER") prevDuration: Long,
        @Suppress("UNUSED_PARAMETER") prevPosition: Long,
    ) {
        val playing = isPlaying
        val state = playbackState
        if (prevPlaying != playing) {
            listeners.forEach { it.onIsPlayingChanged(playing) }
            listeners.forEach {
                it.onPlayWhenReadyChanged(playing, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            }
        }
        if (prevBuffering != overlayBuffering || prevPlaying != playing) {
            listeners.forEach { it.onPlaybackStateChanged(state) }
        }
        val commands = availableCommands
        if (commands != prevCommands) {
            listeners.forEach { it.onAvailableCommandsChanged(commands) }
        }
    }

    override fun isPlaying(): Boolean =
        if (spotifyActive) overlayPlaying else super.isPlaying()

    override fun getPlaybackState(): Int = when {
        spotifyActive && overlayBuffering -> Player.STATE_BUFFERING
        spotifyActive -> Player.STATE_READY
        else -> super.getPlaybackState()
    }

    override fun getCurrentPosition(): Long =
        if (spotifyActive) overlayPositionMs else super.getCurrentPosition()

    override fun getDuration(): Long =
        if (spotifyActive) overlayDurationMs else super.getDuration()

    override fun getContentPosition(): Long = currentPosition

    override fun seekToNextMediaItem() {
        AndroidMediaControls.skipToNext()
    }

    override fun seekToPreviousMediaItem() {
        AndroidMediaControls.skipToPrevious()
    }

    override fun seekToNext() {
        AndroidMediaControls.skipToNext()
    }

    override fun seekToPrevious() {
        AndroidMediaControls.skipToPrevious()
    }

    override fun seekTo(positionMs: Long) {
        if (spotifyActive) {
            AndroidMediaControls.seekTo(positionMs)
            overlayPositionMs = positionMs
        } else {
            super.seekTo(positionMs)
        }
    }

    override fun seekTo(mediaItemIndex: Int, positionMs: Long) {
        if (spotifyActive) {
            seekTo(positionMs)
        } else {
            super.seekTo(mediaItemIndex, positionMs)
        }
    }

    override fun play() {
        if (spotifyActive) {
            AndroidMediaControls.play()
        } else {
            // After clearQueue the session owns transport; do not revive retained Exo items.
            if (exo.mediaItemCount == 0) {
                AndroidMediaControls.play()
            } else {
                super.play()
            }
        }
    }

    override fun pause() {
        if (spotifyActive) {
            AndroidMediaControls.pause()
        } else {
            // Empty Exo playlist: do not treat as a user pause. MediaController silence
            // setup and clearQueue leftovers must not cancel PlayerSession buffering.
            if (exo.mediaItemCount == 0) return
            super.pause()
        }
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) {
        if (spotifyActive) {
            if (playWhenReady) AndroidMediaControls.play() else AndroidMediaControls.pause()
        } else if (exo.mediaItemCount == 0) {
            // Play while empty routes to the session. Pause while empty must no-op so
            // internal MediaController prepare/pause cannot cancel a buffering start.
            if (playWhenReady) AndroidMediaControls.play()
        } else {
            super.setPlayWhenReady(playWhenReady)
        }
    }

    override fun getPlayWhenReady(): Boolean =
        if (spotifyActive) overlayPlaying else super.getPlayWhenReady()

    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        Player.COMMAND_SEEK_TO_NEXT,
        Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
        -> canSkipNext
        Player.COMMAND_SEEK_TO_PREVIOUS,
        Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
        -> canSkipPrevious
        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
        Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
        -> canSeek
        else -> super.isCommandAvailable(command)
    }

    override fun getAvailableCommands(): Player.Commands {
        val builder = super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        if (!canSkipNext) {
            builder.remove(Player.COMMAND_SEEK_TO_NEXT)
            builder.remove(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
        }
        if (!canSkipPrevious) {
            builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS)
            builder.remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
        }
        if (!canSeek) {
            builder.remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        }
        return builder.build()
    }
}
