package com.universalmusic.player.platform

import kotlinx.coroutines.test.*
import kotlin.test.*
import com.universalmusic.player.platform.SpotifyReceiverEvent.Kind

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SpotifyHaltFilterTest {
    @Test fun realPhoneHaltsDoNotFloodPlaybackState() = runTest {
        val output = mutableListOf<SpotifyReceiverEvent>()
        val traces = mutableListOf<String>()
        val filter = SpotifyHaltFilter(backgroundScope, trace = traces::add, emit = output::add)
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
        filter.reset()
        println("Phone replay trace lines: ${traces.size}")
        assertTrue(traces.size < 100, "raw underflows must not write trace lines: ${traces.size}")
        // The capture holds 18 halts that lasted over 250 ms; every shorter flip is start-up or end-of-track chatter.
        assertEquals(18, traces.count { it.startsWith("stall at ") })
        assertTrue(output.size < 100, "start-of-track chatter must not reach transport: ${output.size}")
        assertEquals(18, output.count { it.kind == Kind.BUFFERING }, "every sustained halt is still reported")
    }

    @Test fun resumingMidTrackDoesNotFloodPlaybackState() = runTest {
        val output = mutableListOf<SpotifyReceiverEvent>()
        val filter = SpotifyHaltFilter(backgroundScope, emit = output::add)
        filter.accept(SpotifyReceiverEvent(Kind.PLAYING, "a", 4_376))
        repeat(40) { i ->
            filter.accept(SpotifyReceiverEvent(Kind.BUFFERING, "a", 4_376L + i))
            advanceTimeBy(5); runCurrent()
            filter.accept(SpotifyReceiverEvent(Kind.PLAYING, "a", 4_376L + i))
        }
        advanceTimeBy(300); runCurrent()
        assertEquals(listOf(Kind.PLAYING), output.map { it.kind })
    }

    @Test fun traceContainsOnlyReportedStallsRecoveriesAndOneStartSummary() = runTest {
        val traces = mutableListOf<String>()
        val filter = SpotifyHaltFilter(backgroundScope, trace = traces::add, emit = {})
        fun event(kind: Kind, position: Long) = filter.accept(SpotifyReceiverEvent(kind, "a", position))
        event(Kind.PLAYING, 0)
        event(Kind.BUFFERING, 50)
        advanceTimeBy(100); runCurrent()
        event(Kind.PLAYING, 100)
        assertEquals(emptyList(), traces)
        event(Kind.BUFFERING, 150)
        advanceTimeBy(251); runCurrent()
        assertEquals(listOf("stall at 150"), traces)
        event(Kind.PLAYING, 400)
        event(Kind.PLAYING, 450)
        filter.reset()
        filter.reset()
        assertEquals(listOf("stall at 150", "stall recovered at 400", "start underflows absorbed=1"), traces)
    }

    @Test fun stallsAreDelayedButPauseEndSeekAndNewTracksAreImmediate() = runTest {
        val output = mutableListOf<SpotifyReceiverEvent>()
        val filter = SpotifyHaltFilter(backgroundScope, emit = output::add)
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
        assertEquals(Kind.SEEKED, output.last().kind)
        advanceTimeBy(251); runCurrent()
        assertEquals(Kind.BUFFERING, output.last().kind)
        filter.reset()
        event(Kind.PLAYING, "b")
        advanceTimeBy(300); runCurrent()
        assertEquals("b", output.last().trackId)
        assertEquals(Kind.PLAYING, output.last().kind)
    }
}
