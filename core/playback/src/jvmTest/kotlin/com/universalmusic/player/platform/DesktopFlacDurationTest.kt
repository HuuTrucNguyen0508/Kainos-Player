package com.universalmusic.player.platform

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopFlacDurationTest {
    private fun duration(bytes: ByteArray): Long? {
        val file = Files.createTempFile("kainos-streaminfo-", ".flac")
        try {
            Files.write(file, bytes)
            val type = Class.forName("com.universalmusic.player.platform.SystemDesktopPlaybackRuntime")
            val runtime = type.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            return type.getDeclaredMethod("readLocalFlacDurationMs", String::class.java)
                .apply { isAccessible = true }.invoke(runtime, file.toString()) as Long?
        } finally { Files.deleteIfExists(file) }
    }

    private fun flac(bits: Int = 16, minBlock: Int = 16, maxBlock: Int = 4096,
                     sampleRate: Long = 44100, samples: Long = 88200): ByteArray {
        val bytes = ByteArray(44)
        "fLaC".toByteArray().copyInto(bytes)
        bytes[4] = 0x80.toByte()
        bytes[7] = 34
        bytes[8] = (minBlock ushr 8).toByte(); bytes[9] = minBlock.toByte()
        bytes[10] = (maxBlock ushr 8).toByte(); bytes[11] = maxBlock.toByte()
        val packed = (sampleRate shl 44) or (1L shl 41) or ((bits - 1).toLong() shl 36) or samples
        for (i in 0..7) bytes[18 + i] = (packed ushr (56 - 8 * i)).toByte()
        bytes[42] = 0xff.toByte(); bytes[43] = 0xf8.toByte()
        return bytes
    }

    @Test fun truncatedAfterStreaminfoFallsBack() { assertNull(duration(flac().copyOf(42))) }
    @Test fun badBitsPerSampleFallsBack() { assertNull(duration(flac(bits = 3))) }
    @Test fun reversedBlockSizesFallBack() { assertNull(duration(flac(minBlock = 4096, maxBlock = 16))) }
    @Test fun invalidRateAndUnknownSamplesFallBack() {
        assertNull(duration(flac(sampleRate = 655351)))
        assertNull(duration(flac(sampleRate = 0)))
        assertNull(duration(flac(samples = 0)))
    }
    @Test fun metadataChainMustEndAtAudioSync() {
        val header = flac().copyOf(42).apply { this[4] = 0 }
        assertEquals(2000L, duration(header + byteArrayOf(0x81.toByte(), 0, 0, 3, 1, 2, 3, 0xff.toByte(), 0xf9.toByte())))
        assertNull(duration(header + byteArrayOf(0x81.toByte(), 0, 0, 3, 1, 2)))
        assertNull(duration(header + byteArrayOf(1, 0, 0, 0, 0xff.toByte(), 0xf8.toByte())))
        assertNull(duration(flac().apply { this[43] = 0 }))
    }
    @Test fun nativeFlacAcceptedAndId3FallsBack() {
        assertEquals(2000L, duration(flac()))
        assertNull(duration("ID3".toByteArray() + flac()))
    }
}
