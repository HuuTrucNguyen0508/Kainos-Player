package com.universalmusic.player.domain.playback

import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.PortableListeningQueue
import com.universalmusic.player.data.session.ProjectionFailure
import com.universalmusic.player.data.session.QueueProjection
import com.universalmusic.player.data.sync.REMOTE_QUEUE_CAP
import com.universalmusic.player.data.sync.RemoteCommand
import com.universalmusic.player.data.sync.RemoteCommandExpired
import com.universalmusic.player.data.sync.RemoteCommandReply
import com.universalmusic.player.data.sync.RemotePlayer
import com.universalmusic.player.data.sync.RemotePlayerState
import com.universalmusic.player.data.sync.RemoteQueueEntry
import com.universalmusic.player.data.sync.RemoteRepeat
import com.universalmusic.player.data.sync.RemoteStatus
import com.universalmusic.player.data.sync.RemoteTransferFailure
import com.universalmusic.player.data.sync.RemoteTransferReply
import com.universalmusic.player.data.sync.RemoteTransferSnapshot
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.sha256Bytes
import kotlin.time.TimeMark
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext

/**
 * Exposes [session] to a remote device. Commands name queue entries by item id, never by index,
 * so a stale view cannot play the wrong track; an unknown id does nothing.
 */
class RemotePlayerAdapter(
    private val session: PlayerSession,
    scope: CoroutineScope,
    private val clock: () -> Long = ::currentTimeMillis,
) : RemotePlayer {
    override val state: StateFlow<RemotePlayerState?> =
        combine(session.nowPlaying, session.queueState, session.volume) { now, queue, volume ->
            capture(now, queue, volume, session.canSkipNext())
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override suspend fun apply(command: RemoteCommand, deadline: TimeMark?): RemoteCommandReply = withContext(NonCancellable) {
        session.onSessionThread {
            if (deadline?.hasPassedNow() == true) throw RemoteCommandExpired()
            val transfer = applyNow(command)
            val now = session.nowPlaying.value
            val queue = session.queueState.value
            val status = capture(now, queue, session.volume.value, session.canSkipNext())?.status
            RemoteCommandReply(status, transfer)
        }
    }

    private fun applyNow(command: RemoteCommand): RemoteTransferReply? {
        when (command) {
            RemoteCommand.TogglePlay -> session.togglePlayPauseNow()
            RemoteCommand.Play -> session.playTransportNow()
            RemoteCommand.Pause -> session.pauseTransportNow()
            RemoteCommand.Next -> session.skipToNextNow()
            RemoteCommand.Previous -> session.skipToPreviousNow()
            is RemoteCommand.SeekTo -> if (session.queueState.value.current != null) session.seekNow(command.positionMs.coerceAtLeast(0))
            is RemoteCommand.SetVolume -> session.setVolume(command.volume)
            is RemoteCommand.SetShuffle -> if (session.queueState.value.shuffle != command.enabled) session.setShuffleNow(command.enabled)
            is RemoteCommand.SetRepeat -> session.setRepeatNow(command.mode.toRepeatMode())
            is RemoteCommand.PlayItem -> {
                val index = session.queueState.value.items.indexOfFirst { it.id == command.itemId }
                if (index >= 0) session.playQueueIndexNow(index)
            }
            is RemoteCommand.PlayNext -> playNext(command.itemId)
            RemoteCommand.PauseForTransfer -> {
                session.pauseTransportNow()
                return transferSnapshot()
            }
        }
        return null
    }

    /** Moves an entry to right after the current one in playback order. */
    private fun playNext(itemId: String) {
        val queue = session.queueState.value
        val current = queue.current ?: return
        val from = queue.orderIndexOf(itemId)
        val currentPos = queue.orderIndexOf(current.id)
        if (from < 0 || currentPos < 0 || from == currentPos) return
        val to = if (from < currentPos) currentPos else currentPos + 1
        if (from != to) session.moveInPlaybackOrderNow(from, to)
    }

    private fun transferSnapshot(): RemoteTransferReply {
        val queue = session.queueState.value
        val current = queue.current ?: return RemoteTransferReply(failure = RemoteTransferFailure.EMPTY)
        val now = clock()
        val items = queue.items.map { PersistedQueueItem(it.id, it.track.toPersisted(now)) }
        return when (val projection = PortableListeningQueue.project(items)) {
            is QueueProjection.Unsupported -> RemoteTransferReply(failure = projection.reason.toTransferFailure())
            is QueueProjection.Complete -> RemoteTransferReply(
                RemoteTransferSnapshot(
                    items = projection.queue.entries.map { PersistedQueueItem(it.itemId, it.track.track) },
                    currentItemId = current.id,
                    positionMs = session.nowPlaying.value.positionMs.coerceAtLeast(0),
                    shuffle = queue.shuffle,
                    repeat = queue.repeat.toRemote(),
                ),
            )
        }
    }

    private fun capture(
        now: NowPlayingState,
        queue: PlaybackQueue,
        volume: Float,
        canSkipNext: Boolean,
    ): RemotePlayerState? {
        val track = now.track ?: return null
        val art = track.artwork?.url ?: track.album?.artwork?.url
        val remoteUrl = art?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        val localArt = art?.takeIf { remoteUrl == null && it.isLocalArtwork() }
        val upNext = queue.current?.let { listOf(it) + queue.upcoming }.orEmpty()
        val status = RemoteStatus(
            title = track.title,
            artist = track.artistLine.ifBlank { null },
            album = track.album?.title,
            artworkUrl = remoteUrl,
            artworkKey = localArt?.let { artworkKey(track, it) },
            durationMs = now.durationMs ?: track.durationMs,
            positionMs = now.positionMs.coerceAtLeast(0),
            isPlaying = now.isPlaying,
            buffering = now.buffering,
            shuffle = queue.shuffle,
            repeat = queue.repeat.toRemote(),
            volume = volume,
            currentItemId = now.queueItemId,
            queue = upNext.take(REMOTE_QUEUE_CAP).map { it.toRemote() },
            // Previous always acts on a loaded queue: it restarts the track when nothing precedes it.
            canSkipPrevious = queue.current != null,
            canSkipNext = canSkipNext,
        )
        return RemotePlayerState(status, clock(), localArt)
    }
}

private fun com.universalmusic.player.domain.model.QueueItem.toRemote() =
    RemoteQueueEntry(id, track.title, track.artistLine.ifBlank { null }, track.durationMs)

private fun String.isLocalArtwork(): Boolean = startsWith("file:") || startsWith("content:") || startsWith("/")

/** Names the bytes of [location] for [track], so a new cover at the same path gets a new key. */
internal fun artworkKey(track: Track, location: String): String =
    sha256Bytes("${track.canonicalId}\n$location".encodeToByteArray())
        .take(16).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

internal fun RepeatMode.toRemote(): RemoteRepeat = when (this) {
    RepeatMode.OFF -> RemoteRepeat.OFF
    RepeatMode.ALL -> RemoteRepeat.ALL
    RepeatMode.ONE -> RemoteRepeat.ONE
}

internal fun RemoteRepeat.toRepeatMode(): RepeatMode = when (this) {
    RemoteRepeat.OFF -> RepeatMode.OFF
    RemoteRepeat.ALL -> RepeatMode.ALL
    RemoteRepeat.ONE -> RepeatMode.ONE
}

private fun ProjectionFailure.toTransferFailure(): RemoteTransferFailure = when (this) {
    ProjectionFailure.UNSUPPORTED_TRACK -> RemoteTransferFailure.UNSUPPORTED_TRACK
    ProjectionFailure.INVALID_ID -> RemoteTransferFailure.INVALID_ID
    ProjectionFailure.DUPLICATE_ITEM_ID -> RemoteTransferFailure.DUPLICATE_ITEM_ID
}
