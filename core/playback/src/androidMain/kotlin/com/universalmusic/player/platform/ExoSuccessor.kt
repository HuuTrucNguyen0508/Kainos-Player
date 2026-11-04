package com.universalmusic.player.platform

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.PreparedPlayback

/** What an item transition reported by the player means for the engine. */
internal sealed interface Crossing {
    /** Not a crossing into a gapless successor, such as a new track loaded by setMediaItem. */
    data object None : Crossing

    /** Exo crossed into the successor the session still wants: the session adopts it. */
    data class Adopt(val prepared: PreparedPlayback) : Crossing

    /** Exo crossed into a successor the session has since replaced or cancelled. */
    data object Unowned : Crossing
}

/**
 * The one local file Exo holds behind the playing item so it plays on without a gap. The session
 * picks it ([PlaybackEngine.prepareNext]); [want] records that at once, and [sync] later brings the
 * player's playlist in line through the engine's FIFO. A crossing counts only while [want] still
 * names that exact item and generation, so a skip, Play next, shuffle or repeat change that lands
 * after Exo already queued the old file is never adopted. Main thread.
 */
internal class ExoSuccessor {
    private var wanted: PreparedPlayback? = null

    fun want(next: PreparedPlayback?) {
        wanted = next
    }

    /**
     * Leaves the playlist as [current, wanted successor] for [generation]. Items after the
     * current one are removed or appended only; the playing item is never touched.
     */
    fun sync(player: Player, generation: Long, track: (itemId: String) -> Track?) {
        val successor = wanted?.takeIf { it.generation == generation }
        val tail = player.currentMediaItemIndex + 1
        if (tail <= 0) return
        val queued = (tail until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        val id = successor?.let(::mediaId)
        if (queued == listOfNotNull(id)) return
        if (queued.isNotEmpty()) player.removeMediaItems(tail, player.mediaItemCount)
        if (successor != null) {
            val next = track(successor.itemId)
            player.addMediaItem(
                mediaItemForUrl(successor.handle.url, next, next?.durationMs).buildUpon().setMediaId(id!!).build(),
            )
        }
    }

    /** Called for every reported item transition, before anything else reacts to it. */
    fun onTransition(mediaItem: MediaItem?, reason: Int, generation: Long): Crossing {
        val id = mediaItem?.mediaId
        if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || id == null || !id.startsWith(PREFIX)) return Crossing.None
        val successor = wanted?.takeIf { it.generation == generation && mediaId(it) == id } ?: return Crossing.Unowned
        wanted = null
        return Crossing.Adopt(successor)
    }

    /** Drops the items that already played, so the adopted one is first again. */
    fun dropPlayed(player: Player) {
        val current = player.currentMediaItemIndex
        if (current > 0) player.removeMediaItems(0, current)
    }

    private fun mediaId(prepared: PreparedPlayback) = "$PREFIX${prepared.generation}:${prepared.itemId}"

    private companion object {
        const val PREFIX = "kainos-gapless:"
    }
}

/** Only files on this device join gaplessly; streams keep the ordinary start. */
internal fun isLocalMediaUri(url: String): Boolean =
    url.startsWith("content:") || url.startsWith("file:") || url.startsWith("/")
