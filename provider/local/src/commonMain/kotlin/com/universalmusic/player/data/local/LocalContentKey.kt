package com.universalmusic.player.data.local

/** Optional reader used after an Android scan. Unreadable or non-seekable files keep basename fallback. */
fun interface LocalContentKeyReader {
    suspend fun read(location: String): String?
}

expect fun createLocalContentKeyReader(): LocalContentKeyReader?

internal const val LOCAL_CONTENT_TAIL_BYTES = 64 * 1024

/** lc1 encodes file size as an unsigned big-endian eight-byte value before the final 64 KiB. */
internal fun localContentKeyPayload(size: Long, tail: ByteArray): ByteArray {
    require(size >= 0 && tail.size.toLong() == minOf(size, LOCAL_CONTENT_TAIL_BYTES.toLong()))
    return ByteArray(8 + tail.size).also { payload ->
        repeat(8) { index -> payload[index] = (size ushr ((7 - index) * 8)).toByte() }
        tail.copyInto(payload, destinationOffset = 8)
    }
}
