package com.universalmusic.player.platform

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.PreparedPlayback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ExoSuccessorTest {
    private val exo = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
    private val successor = ExoSuccessor()
    private val titles = mapOf("q2" to "Second", "q3" to "Third")

    init {
        exo.setMediaItem(MediaItem.Builder().setUri("content://media/1.flac").setMediaId("local:first").build())
    }

    @After fun release() = exo.release()

    private fun prepared(itemId: String, generation: Long = 7) =
        PreparedPlayback(itemId, PlaybackHandle.Url("content://media/$itemId.flac"), null, generation)

    private fun sync(generation: Long = 7) = successor.sync(exo, generation) { id ->
        titles[id]?.let { Track("local:$id", it, emptyList(), durationMs = 61_000) }
    }

    private fun playlist() = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }

    private fun crossToSuccessor(): Crossing {
        exo.seekTo(1, 0)
        return successor.onTransition(exo.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, 7)
    }

    @Test fun queuesTheWantedFileBehindThePlayingItemWithItsQueueMetadata() {
        successor.want(prepared("q2"))
        sync()
        assertEquals(listOf("local:first", "kainos-gapless:7:q2"), playlist())
        val queued = exo.getMediaItemAt(1)
        assertEquals("content://media/q2.flac", queued.localConfiguration?.uri.toString())
        assertEquals("Second", queued.mediaMetadata.title)
        sync()
        assertEquals("A repeated sync leaves the playlist alone", 2, exo.mediaItemCount)
    }

    @Test fun replacingOrCancellingTheSuccessorOnlyChangesTheTail() {
        successor.want(prepared("q2"))
        sync()
        successor.want(prepared("q3"))
        sync()
        assertEquals(listOf("local:first", "kainos-gapless:7:q3"), playlist())
        successor.want(null)
        sync()
        assertEquals(listOf("local:first"), playlist())
    }

    @Test fun aSuccessorForAnOlderTrackIsNeverQueued() {
        successor.want(prepared("q2", generation = 6))
        sync()
        assertEquals(listOf("local:first"), playlist())
    }

    @Test fun crossingIntoTheWantedSuccessorIsAdoptedOnceAndThePlayedItemDropped() {
        successor.want(prepared("q2"))
        sync()
        assertEquals(Crossing.Adopt(prepared("q2")), crossToSuccessor())
        successor.dropPlayed(exo)
        assertEquals(listOf("kainos-gapless:7:q2"), playlist())
        assertEquals(0, exo.currentMediaItemIndex)
        assertEquals("The same crossing reported twice is not adopted again", Crossing.Unowned,
            successor.onTransition(exo.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, 7))
    }

    @Test fun crossingIntoASuccessorReplacedBeforeTheFifoCaughtUpIsUnowned() {
        successor.want(prepared("q2"))
        sync()
        // Play next / shuffle / skip: the session wants another file, but Exo still holds q2.
        successor.want(prepared("q3"))
        assertEquals(Crossing.Unowned, crossToSuccessor())
    }

    @Test fun crossingAfterTheSessionMovedToANewGenerationIsUnowned() {
        successor.want(prepared("q2"))
        sync()
        exo.seekTo(1, 0)
        assertEquals(Crossing.Unowned,
            successor.onTransition(exo.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO, 8))
    }

    @Test fun ordinaryTrackChangesAreNotCrossings() {
        successor.want(prepared("q2"))
        sync()
        assertEquals(Crossing.None, successor.onTransition(exo.getMediaItemAt(0), Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED, 7))
        exo.seekTo(1, 0)
        assertEquals("A seek into the successor is not an automatic crossing", Crossing.None,
            successor.onTransition(exo.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK, 7))
    }

    @Test fun controllersCannotJumpIntoTheQueuedSuccessor() {
        successor.want(prepared("q2"))
        sync()
        val player = KainosForwardingPlayer(exo, LevellingAudioProcessor())
        assertEquals(false, player.isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
        assertEquals(false, player.availableCommands.contains(Player.COMMAND_SEEK_TO_MEDIA_ITEM))
    }
}
