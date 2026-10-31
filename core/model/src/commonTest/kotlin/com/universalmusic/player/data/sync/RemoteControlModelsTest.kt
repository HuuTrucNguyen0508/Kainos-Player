package com.universalmusic.player.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class RemoteControlModelsTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun commandsTravelWithATypeDiscriminator() {
        val envelope = RemoteEnvelope(7, 120, RemoteCommand.SeekTo(42_000))
        val encoded = json.encodeToString(RemoteEnvelope.serializer(), envelope)

        assertEquals("""{"seq":7,"ageMs":120,"command":{"type":"seek","positionMs":42000}}""", encoded)
        assertEquals(envelope, json.decodeFromString(RemoteEnvelope.serializer(), encoded))
        assertEquals(
            RemoteCommand.PauseForTransfer,
            json.decodeFromString(RemoteCommand.serializer(), """{"type":"pause_for_transfer"}"""),
        )
    }

    @Test
    fun aPositionTickIsNotAMaterialChangeButASeekIs() {
        val playing = RemoteStatus(title = "A", positionMs = 10_000, isPlaying = true, durationMs = 200_000)

        assertFalse(playing.isMaterialChange(playing.copy(positionMs = 15_000), elapsedMs = 5_000))
        assertFalse(playing.isMaterialChange(playing.copy(positionMs = 11_000, positionAgeMs = 3_500), elapsedMs = 5_000))
        assertTrue(playing.isMaterialChange(playing.copy(positionMs = 60_000), elapsedMs = 5_000))
        assertTrue(playing.isMaterialChange(playing.copy(isPlaying = false, positionMs = 15_000), elapsedMs = 5_000))
        assertTrue(playing.isMaterialChange(playing.copy(volume = 0.5f), elapsedMs = 0))
        assertTrue(playing.isMaterialChange(null, elapsedMs = 0))
    }

    @Test
    fun aPausedPositionDoesNotAgeIntoASeek() {
        val paused = RemoteStatus(title = "A", positionMs = 10_000, isPlaying = false)

        assertFalse(paused.isMaterialChange(paused.copy(positionAgeMs = 60_000), elapsedMs = 60_000))
        assertEquals(10_000, paused.positionAfter(60_000))
    }

    @Test
    fun theWirePositionAgeGrowsFromCapture() {
        val state = RemotePlayerState(RemoteStatus(positionMs = 1_000, isPlaying = true), capturedAtMs = 5_000)

        assertEquals(750, state.toWire(5_750).positionAgeMs)
        assertEquals(0, state.toWire(4_000).positionAgeMs)
    }
}
