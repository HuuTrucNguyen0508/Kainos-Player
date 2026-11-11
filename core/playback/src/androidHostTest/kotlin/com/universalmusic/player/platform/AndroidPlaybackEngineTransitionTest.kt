package com.universalmusic.player.platform

import android.content.Context
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Between queue entries the retained Exo item must survive, even when the entry in between
 * never started. Clearing it takes Exo to IDLE, Media3 drops the foreground service, and
 * HyperOS refuses to promote it again for the next start (phone traces 2026-11-03..09).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPlaybackEngineTransitionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val exo = ExoPlayer.Builder(context).build()
    private val forwarding = KainosForwardingPlayer(exo, LevellingAudioProcessor())
    private val spotify = SpotifyPlaybackController(
        play = { error("Unable to resolve host \"api.spotify.com\"") },
        pause = {},
        resume = {},
        seekTo = {},
    )
    private val engine = AndroidPlaybackEngine(context, spotify) { forwarding }

    init {
        AndroidMediaControls.attachPlayer(forwarding)
        idleMain()
    }

    @After fun release() {
        AndroidMediaControls.attachPlayer(null)
        idleMain()
        exo.release()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs a suspending engine call off Main while the test thread pumps the main looper. */
    private fun drive(block: suspend () -> Unit): Result<Unit> {
        val call = CoroutineScope(Dispatchers.Default).async { runCatching { block() } }
        val deadline = System.currentTimeMillis() + 10_000
        while (!call.isCompleted) {
            check(System.currentTimeMillis() < deadline) { "engine call did not finish" }
            idleMain()
            Thread.sleep(2)
        }
        idleMain()
        return call.getCompleted()
    }

    private fun playUrl(name: String, generation: Long) =
        drive { engine.play(PlaybackHandle.Url("content://media/$name.flac"), null, generation) }.getOrThrow()

    private fun transition() {
        engine.stopForTransition()
        idleMain()
    }

    private fun retainedItem() = exo.currentMediaItem?.localConfiguration?.uri?.toString()

    @Test fun anEntryThatFailedToResolveDoesNotClearTheRetainedItemOnTheNextSkip() {
        playUrl("first", 1)
        transition()
        // The next entry's file is missing: the session never reaches engine.play and skips again.
        transition()
        assertEquals("content://media/first.flac", retainedItem())
        assertNotEquals("Exo stays out of IDLE between queue entries", Player.STATE_IDLE, exo.playbackState)
        assertEquals(false, exo.playWhenReady)
        playUrl("third", 3)
        assertEquals("content://media/third.flac", retainedItem())
        assertTrue(exo.playWhenReady)
    }

    @Test fun aFailedSpotifyStartDoesNotClearTheRetainedItemOnTheNextSkip() {
        playUrl("first", 1)
        transition()
        val failed = drive { engine.play(PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, "spotify:track:x", 200_000), null, 2) }
        assertTrue(failed.isFailure)
        transition()
        transition()
        assertEquals(1, exo.mediaItemCount)
        assertNotEquals(Player.STATE_IDLE, exo.playbackState)
    }

    @Test fun controllerPlayCannotReviveAnItemRetainedAcrossFailedEntries() {
        playUrl("first", 1)
        transition()
        transition()
        forwarding.playWhenReady = false
        assertEquals(false, exo.playWhenReady)
        forwarding.play()
        assertEquals(false, exo.playWhenReady)
        forwarding.playWhenReady = true
        assertEquals(false, exo.playWhenReady)
        // The engine knows the next URL before MediaController has delivered setMediaItem.
        forwarding.expectTransitionMedia("content://media/third.flac")
        forwarding.play()
        assertEquals(false, exo.playWhenReady)
        forwarding.playWhenReady = true
        assertEquals(false, exo.playWhenReady)
        playUrl("third", 3)
        assertTrue(exo.playWhenReady)
    }

    @Test fun anExplicitStopStillReleasesTheItem() {
        playUrl("first", 1)
        engine.stop()
        idleMain()
        assertEquals(0, exo.mediaItemCount)
        assertEquals(Player.STATE_IDLE, exo.playbackState)
    }
}
