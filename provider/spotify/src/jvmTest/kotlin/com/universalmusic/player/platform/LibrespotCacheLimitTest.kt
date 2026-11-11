package com.universalmusic.player.platform

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LibrespotCacheLimitTest {
    @Test
    fun preservesTheJournalAndChunksAtTheLimit() = withCache { cache ->
        File(cache, "journal.dat").writeBytes(ByteArray(4))
        val chunk = File(cache, "ab/abcdef").also { it.parentFile.mkdirs() }
        chunk.writeBytes(ByteArray(6))

        assertNull(resetOversizedLibrespotCache(cache, limitBytes = 10))
        assertEquals(4L, File(cache, "journal.dat").length())
        assertEquals(6L, chunk.length())
    }

    @Test
    fun removesTheEntireJournalAndChunksWhenOverTheLimit() = withCache { cache ->
        File(cache, "journal.dat").writeBytes(ByteArray(4))
        File(cache, "ab/abcdef").also { it.parentFile.mkdirs() }.writeBytes(ByteArray(7))

        assertEquals(11L, resetOversizedLibrespotCache(cache, limitBytes = 10))
        assertFalse(cache.exists())
        assertNull(resetOversizedLibrespotCache(cache, limitBytes = 10))
    }

    @Test
    fun theProductionLimitIsOneGiB() {
        assertEquals(1_073_741_824L, LIBRESPOT_CACHE_LIMIT_BYTES)
    }

    private fun withCache(test: (File) -> Unit) {
        val root = Files.createTempDirectory("librespot-cache-test").toFile()
        try {
            test(File(root, "cache").also { it.mkdir() })
        } finally {
            root.deleteRecursively()
        }
    }
}
