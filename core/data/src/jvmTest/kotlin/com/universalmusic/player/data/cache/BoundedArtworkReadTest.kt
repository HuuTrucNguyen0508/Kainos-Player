package com.universalmusic.player.data.cache

import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

private class CountingStream(source: InputStream) : FilterInputStream(source) {
    var consumed = 0L
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it > 0) consumed += it }
}

class BoundedArtworkReadTest {
    private fun stream(size: Int) = CountingStream(ByteArrayInputStream(ByteArray(size) { it.toByte() }))

    @Test fun oversizedArtworkIsRejectedAfterReadingAtMostOneByteOverTheLimit() {
        val input = stream(MAX_ARTWORK_BYTES * 5)
        assertNull(readBoundedArtwork(input::read))
        assertEquals(MAX_ARTWORK_BYTES + 1L, input.consumed)
    }

    @Test fun artworkOfExactlyTheLimitIsKept() {
        val input = stream(MAX_ARTWORK_BYTES)
        assertEquals(MAX_ARTWORK_BYTES, readBoundedArtwork(input::read)?.size)
    }

    @Test fun smallArtworkKeepsItsBytes() {
        val input = stream(5)
        assertContentEquals(byteArrayOf(0, 1, 2, 3, 4), readBoundedArtwork(input::read))
    }

    @Test fun emptyArtworkIsRejected() {
        assertNull(readBoundedArtwork(stream(0)::read))
    }
}
