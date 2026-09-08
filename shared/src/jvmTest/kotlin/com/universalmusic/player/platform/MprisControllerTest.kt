package com.universalmusic.player.platform

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PlayerSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MPRIS registration is best-effort against the session bus. Wiring tests do not require D-Bus;
 * [livePlayerctlSeesSessionMetadata] runs only when a session bus + playerctl are available.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MprisControllerTest {
    @Test
    fun bindStartsWithoutThrowingWhenSessionBusMissing() = runTest {
        val engine = RecordingEngineForMpris()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        val controller = MprisController(session, backgroundScope)
        controller.start()
        session.play(track("One", "A", provider = ProviderId.SAMPLE))
        runCurrent()
        assertEquals("One", session.nowPlaying.value.track?.title)
        controller.stop()
    }

    @Test
    fun playerSkipMethodsReachSessionQueue() = runTest {
        val engine = RecordingEngineForMpris()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        session.play(
            listOf(
                track("One", "A", provider = ProviderId.SAMPLE),
                track("Two", "B", provider = ProviderId.SAMPLE),
            ),
            startIndex = 0,
        )
        runCurrent()
        session.skipToNext()
        runCurrent()
        assertEquals("Two", session.nowPlaying.value.track?.title)
    }

    @Test
    fun livePlayerctlSeesSessionMetadata() = runBlocking(Dispatchers.Default) {
        if (System.getenv("DBUS_SESSION_BUS_ADDRESS").isNullOrBlank()) return@runBlocking
        if (runCatching { ProcessBuilder("playerctl", "--version").start().waitFor() }.getOrNull() != 0) {
            return@runBlocking
        }
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val engine = RecordingEngineForMpris()
        val session = PlayerSession(engine, DefaultSourceResolver(), scope)
        val controller = MprisController(session, scope)
        controller.start()
        try {
            session.play(
                listOf(
                    track("MprisSmokeOne", "ArtistA", provider = ProviderId.SAMPLE),
                    track("MprisSmokeTwo", "ArtistB", provider = ProviderId.SAMPLE),
                ),
                startIndex = 0,
            )
            delay(1_000)
            val listed = playerctl("-l")
            assertTrue(
                listed.lineSequence().any { it.trim() == "kainosplayer" },
                "playerctl -l missing kainosplayer: $listed",
            )
            val dbusTitle = dbusGetTitle()
            assertTrue(
                dbusTitle.contains("MprisSmokeOne"),
                "D-Bus Metadata missing title. Got: $dbusTitle",
            )
            val title = playerctl("-p", "kainosplayer", "metadata", "xesam:title").trim()
            if (!title.contains("No player") && title.isNotBlank()) {
                assertEquals("MprisSmokeOne", title)
            }
            dbusCall("Next")
            delay(500)
            assertEquals("MprisSmokeTwo", session.nowPlaying.value.track?.title)
            val dbusTitle2 = dbusGetTitle()
            assertTrue(
                dbusTitle2.contains("MprisSmokeTwo"),
                "After Next, Metadata should be second track. Got: $dbusTitle2",
            )
        } finally {
            controller.stop()
            scope.cancel()
        }
    }
}

private fun playerctl(vararg args: String): String {
    val process = ProcessBuilder(listOf("playerctl") + args).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return out
}

private fun dbusGetTitle(): String {
    val process = ProcessBuilder(
        "dbus-send",
        "--session",
        "--print-reply",
        "--dest=org.mpris.MediaPlayer2.kainosplayer",
        "/org/mpris/MediaPlayer2",
        "org.freedesktop.DBus.Properties.Get",
        "string:org.mpris.MediaPlayer2.Player",
        "string:Metadata",
    ).redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    process.waitFor()
    return out
}

private fun dbusCall(method: String) {
    ProcessBuilder(
        "dbus-send",
        "--session",
        "--type=method_call",
        "--dest=org.mpris.MediaPlayer2.kainosplayer",
        "/org/mpris/MediaPlayer2",
        "org.mpris.MediaPlayer2.Player.$method",
    ).redirectErrorStream(true).start().waitFor()
}

private class RecordingEngineForMpris : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    private var activePlayGeneration: Long = 0L
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        activePlayGeneration = playGeneration
        state.value = EngineState(
            status = EngineStatus.PLAYING,
            durationMs = 1_000,
            playGeneration = playGeneration,
        )
    }
    override fun pause() {
        state.value = state.value.copy(status = EngineStatus.PAUSED, playGeneration = activePlayGeneration)
    }
    override fun resume() {
        state.value = state.value.copy(status = EngineStatus.PLAYING, playGeneration = activePlayGeneration)
    }
    override fun seekTo(positionMs: Long) {
        state.value = state.value.copy(positionMs = positionMs, playGeneration = activePlayGeneration)
    }
    override fun stop() {
        state.value = EngineState(playGeneration = activePlayGeneration)
    }
    override fun setVolume(volume: Float) = Unit
}
