package com.universalmusic.player.platform

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Remembers the last few bursts of traffic on one librespot AP connection, so a trace can show
 * what happened just before the connection dropped. The connection is encrypted, so only times and
 * sizes are known. Spotify sends an 11-byte keepalive every 2 minutes and expects an 11-byte answer;
 * the report shows whether that keepalive arrived, how fast it was answered, and how the read ended.
 */
internal class ApTrafficLog(private val clockMs: () -> Long) {
    private class Burst(val atMs: Long, val inbound: Boolean, var bytes: Long, var lastMs: Long)

    private val bursts = ArrayDeque<Burst>()
    private var ending: Pair<Long, String>? = null

    fun input(stream: InputStream): InputStream = object : FilterInputStream(stream) {
        override fun read(): Int = observeRead { super.read() }.also { if (it >= 0) record(inbound = true, 1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            observeRead { super.read(b, off, len) }.also { if (it > 0) record(inbound = true, it.toLong()) }
    }

    fun output(stream: OutputStream): OutputStream = object : FilterOutputStream(stream) {
        override fun write(b: Int) = observeWrite(1) { out.write(b) }

        // FilterOutputStream writes a byte array one byte at a time; pass it through whole.
        override fun write(b: ByteArray, off: Int, len: Int) = observeWrite(len.toLong()) { out.write(b, off, len) }
    }

    /** What the connection did before it dropped, newest last, with times relative to now. */
    fun report(): String = synchronized(this) {
        val now = clockMs()
        if (bursts.isEmpty() && ending == null) return "no AP traffic recorded"
        val parts = bursts.map { burst ->
            val direction = if (burst.inbound) "in" else "out"
            "$direction ${burst.bytes} B ${seconds(now - burst.atMs)}"
        } + listOfNotNull(ending?.let { (atMs, how) -> "read ended ${seconds(now - atMs)}: $how" })
        parts.joinToString(", ")
    }

    private inline fun observeRead(read: () -> Int): Int {
        val result = try {
            read()
        } catch (failure: IOException) {
            end("${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        }
        if (result < 0) end("end of stream (Spotify closed it)")
        return result
    }

    private inline fun observeWrite(bytes: Long, write: () -> Unit) {
        try {
            write()
        } catch (failure: IOException) {
            end("write failed, ${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        }
        record(inbound = false, bytes)
    }

    private fun record(inbound: Boolean, bytes: Long) = synchronized(this) {
        val now = clockMs()
        val last = bursts.lastOrNull()
        // A packet arrives as several reads (header, then body); count them as one burst.
        if (last != null && last.inbound == inbound && now - last.lastMs <= BURST_GAP_MS) {
            last.bytes += bytes
            last.lastMs = now
        } else {
            bursts.addLast(Burst(now, inbound, bytes, now))
            while (bursts.size > MAX_BURSTS) bursts.removeFirst()
        }
    }

    private fun end(how: String) = synchronized(this) {
        if (ending == null) ending = clockMs() to how
    }

    private fun seconds(agoMs: Long) = String.format(Locale.ROOT, "%.1f s ago", agoMs / 1000.0)

    private companion object {
        const val BURST_GAP_MS = 100L
        const val MAX_BURSTS = 10
    }
}
