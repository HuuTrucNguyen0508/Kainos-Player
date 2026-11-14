package com.universalmusic.player.platform

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.platform.SpotifyReceiverEvent.Kind
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * With the next track queued on the receiver, librespot ends the old track and moves into the new
 * one before Kainos starts it: END_OF_TRACK for the old id, then the new id's own events. The old
 * turn must end exactly once and ignore the new id; the new turn starts from the adoption.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPlaybackEngineSpotifyAdvanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val exo = ExoPlayer.Builder(context).build()
    private val forwarding = KainosForwardingPlayer(exo, LevellingAudioProcessor())
    private val receiver = MutableSharedFlow<SpotifyReceiverEvent>(extraBufferCapacity = 64)
    /** What the receiver reports while each track starts: the load, or the adoption of a queued track. */
    private val onStart = mutableMapOf<String, List<SpotifyReceiverEvent>>()
    private val pauses = Collections.synchronizedList(mutableListOf<String>())
    private val spotify = SpotifyPlaybackController(
        play = { trackId -> onStart[trackId.removePrefix("spotify:track:")].orEmpty().forEach { receiver.emit(it) } },
        pause = { pauses += "pause" },
        pauseForTransition = { pauses += "transition" },
        resume = {},
        seekTo = {},
        receiverEvents = object : SpotifyReceiverEvents {
            override val events = receiver
            override val isAvailable = true
        },
    )
    private val engine = AndroidPlaybackEngine(context, spotify) { forwarding }
    private val watcher = CoroutineScope(Dispatchers.Default)
    private val history = Collections.synchronizedList(mutableListOf<EngineState>())

    init {
        AndroidMediaControls.attachPlayer(forwarding)
        idleMain()
        watcher.launch { engine.state.collect { history += it } }
    }

    @After fun release() {
        watcher.cancel()
        AndroidMediaControls.attachPlayer(null)
        idleMain()
        exo.release()
    }

    private fun idleMain() = shadowOf(Looper.getMainLooper()).idle()

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

    private fun awaitState(what: String, predicate: (EngineState) -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!predicate(engine.state.value)) {
            check(System.currentTimeMillis() < deadline) { "engine never reached $what: ${engine.state.value}" }
            idleMain()
            Thread.sleep(2)
        }
    }

    /** Lets the receiver collector and the main looper drain. */
    private fun settle() = repeat(25) {
        idleMain()
        Thread.sleep(4)
    }

    private fun spotify(id: String) = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, "spotify:track:$id", 200_000)

    @Test fun librespotsOwnAdvanceEndsTheOldTurnOnceAndStartsTheNextFromTheAdoption() {
        onStart["a"] = listOf(SpotifyReceiverEvent(Kind.PLAYING, "a", 0))
        drive { engine.play(spotify("a"), null, 1) }.getOrThrow()
        awaitState("PLAYING a") { it.status == EngineStatus.PLAYING && it.playGeneration == 1L }

        receiver.tryEmit(SpotifyReceiverEvent(Kind.SEEKED, "a", 198_500))
        settle()
        receiver.tryEmit(SpotifyReceiverEvent(Kind.END_OF_TRACK, "a"))
        // librespot moved into the queued track: its own events, before Kainos starts it.
        receiver.tryEmit(SpotifyReceiverEvent(Kind.PLAYING, "b", 40))
        receiver.tryEmit(SpotifyReceiverEvent(Kind.PAUSED, "b", 60))
        receiver.tryEmit(SpotifyReceiverEvent(Kind.PLAYING, "b", 80))
        awaitState("ENDED a") { it.status == EngineStatus.ENDED }
        settle()

        val firstTurn = history.filter { it.playGeneration == 1L }.map { it.status }
        assertEquals(EngineStatus.ENDED, engine.state.value.status)
        assertEquals(1, firstTurn.count { it == EngineStatus.ENDED })
        assertEquals(EngineStatus.ENDED, firstTurn.last())

        // The session moves on: transition, then start b, which the receiver adopts.
        engine.stopForTransition()
        settle()
        receiver.tryEmit(SpotifyReceiverEvent(Kind.PAUSED, "a", 200_000))
        onStart["b"] = listOf(SpotifyReceiverEvent(Kind.PLAYING, "b", 450))
        drive { engine.play(spotify("b"), null, 2) }.getOrThrow()
        awaitState("PLAYING b") { it.status == EngineStatus.PLAYING && it.playGeneration == 2L }

        assertEquals(1, history.filter { it.playGeneration == 1L }.count { it.status == EngineStatus.ENDED })
        assertEquals(listOf(EngineStatus.BUFFERING, EngineStatus.PLAYING),
            history.filter { it.playGeneration == 2L }.map { it.status }.distinct())
        assertEquals(true, engine.state.value.positionMs in 450L..2_000L)
        // Only Kainos's own boundary pauses may be held by the receiver; none of them was a listener pause.
        assertEquals(listOf("transition", "transition"), pauses.toList())
    }

    @Test fun aListenersPauseIsNeverSentAsATransitionPause() {
        onStart["a"] = listOf(SpotifyReceiverEvent(Kind.PLAYING, "a", 0))
        drive { engine.play(spotify("a"), null, 1) }.getOrThrow()
        awaitState("PLAYING a") { it.status == EngineStatus.PLAYING }

        engine.pause()
        settle()

        assertEquals(listOf("pause"), pauses.toList())
    }
}
