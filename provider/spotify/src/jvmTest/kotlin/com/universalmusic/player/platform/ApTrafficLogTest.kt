package com.universalmusic.player.platform

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ApTrafficLogTest {
    private var now = 0L
    private val log = ApTrafficLog { now }

    /** Hands out queued chunks at set times, then fails the way a reset socket does. */
    private inner class FakeSocketInput(private val chunks: ArrayDeque<Pair<Long, ByteArray>>) : InputStream() {
        private var pending = ByteArray(0)
        private var offset = 0

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (offset == pending.size) {
                val (atMs, chunk) = chunks.removeFirstOrNull() ?: throw SocketException("Connection reset")
                now = atMs
                pending = chunk
                offset = 0
            }
            val count = minOf(len, pending.size - offset)
            pending.copyInto(b, off, offset, offset + count)
            offset += count
            return count
        }
    }

    @Test
    fun reportsKeepalivesTheirAnswersAndHowTheReadEnded() {
        val input = DataInputStream(
            log.input(
                FakeSocketInput(
                    ArrayDeque(
                        listOf(
                            1_000L to ByteArray(11),
                            121_000L to ByteArray(11),
                        ),
                    ),
                ),
            ),
        )
        val output = log.output(ByteArrayOutputStream())

        // librespot reads the 3-byte header and the 8-byte body separately; the log keeps one burst.
        input.readFully(ByteArray(3))
        input.readFully(ByteArray(8))
        now = 1_004L
        output.write(ByteArray(11))
        input.readFully(ByteArray(11))
        now = 121_002L
        output.write(ByteArray(11))
        now = 122_000L
        assertFailsWith<IOException> { input.readFully(ByteArray(3)) }

        now = 122_500L
        assertEquals(
            "in 11 B 121.5 s ago, out 11 B 121.5 s ago, in 11 B 1.5 s ago, out 11 B 1.5 s ago, " +
                "read ended 0.5 s ago: SocketException: Connection reset",
            log.report(),
        )
    }

    @Test
    fun reportsSpotifyClosingTheConnection() {
        val input = log.input(InputStream.nullInputStream())
        now = 5_000L
        assertEquals(-1, input.read(ByteArray(3), 0, 3))
        now = 6_000L
        assertEquals("read ended 1.0 s ago: end of stream (Spotify closed it)", log.report())
    }
}
