package com.universalmusic.player.platform

import kotlinx.coroutines.test.*
import kotlin.test.*
import com.universalmusic.player.platform.SpotifyReceiverEvent.Kind

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpotifyHaltFilterTest {
    @Test fun realPhoneHaltsDoNotFloodPlaybackState() = runTest {
        val output = mutableListOf<SpotifyReceiverEvent>()
        val filter = SpotifyHaltFilter(backgroundScope, output::add)
        val rows = javaClass.getResourceAsStream("/spotify-halts-2026-11-02.tsv")!!.bufferedReader().readLines()
        var previous = 0L
        rows.forEach { row ->
            val (time, halted, position) = row.split('\t')
            advanceTimeBy(time.toLong() - previous)
            runCurrent()
            if (halted == "reset") filter.reset()
            else if (halted == "ready") Unit
            else filter.accept(SpotifyReceiverEvent(if (halted == "true") Kind.BUFFERING else Kind.PLAYING, "track", position.toLong()))
            runCurrent()
            previous = time.toLong()
        }
        advanceTimeBy(251)
        runCurrent()
        println("Phone replay: ${rows.count { it.contains("\ttrue\t") || it.contains("\tfalse\t") }} raw halt events -> ${output.size} state events, ${output.count { it.kind == Kind.BUFFERING }} sustained stalls")
        assertTrue(output.size < 100, "start-of-track chatter must not reach transport: ${output.size}")
        assertEquals(9, output.count { it.kind == Kind.BUFFERING && (it.positionMs ?: 0) > 1_000 }, "all nine real mid-track stalls remain visible immediately")
    }

    @Test fun stallsAreDelayedButPauseEndSeekAndNewTracksAreImmediate() = runTest {
        val output = mutableListOf<SpotifyReceiverEvent>()
        val filter = SpotifyHaltFilter(backgroundScope, output::add)
        fun event(kind: Kind, track: String = "a") = filter.accept(SpotifyReceiverEvent(kind, track, 40))
        event(Kind.PLAYING)
        event(Kind.BUFFERING)
        advanceTimeBy(200); runCurrent()
        event(Kind.PLAYING)
        assertEquals(listOf(Kind.PLAYING), output.map { it.kind })
        event(Kind.BUFFERING)
        advanceTimeBy(251); runCurrent()
        assertEquals(Kind.BUFFERING, output.last().kind)
        event(Kind.PAUSED)
        event(Kind.END_OF_TRACK)
        event(Kind.SEEKED)
        assertEquals(listOf(Kind.PAUSED, Kind.END_OF_TRACK, Kind.SEEKED), output.takeLast(3).map { it.kind })
        filter.accept(SpotifyReceiverEvent(Kind.BUFFERING, "a", 5_000))
        assertEquals(Kind.BUFFERING, output.last().kind)
        filter.reset()
        event(Kind.PLAYING, "b")
        advanceTimeBy(300); runCurrent()
        assertEquals("b", output.last().trackId)
        assertEquals(Kind.PLAYING, output.last().kind)
    }
}
