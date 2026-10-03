package com.universalmusic.player.data.local

import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class LocalContentKeyTest {
    @Test
    fun keyMatchesSizeAndTailFormulaAndSurvivesRename() {
        val directory = Files.createTempDirectory("kainos-key")
        try {
            val bytes = ByteArray(100_000) { (it % 251).toByte() }
            val file = directory.resolve("first.flac")
            Files.write(file, bytes)
            val expected = "lc1:" + MessageDigest.getInstance("SHA-256")
                .digest(localContentKeyPayload(bytes.size.toLong(), bytes.takeLast(65_536).toByteArray()))
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, file.readLocalContentKey())
            val renamed = Files.move(file, directory.resolve("renamed.flac"))
            assertEquals(expected, renamed.readLocalContentKey())
            bytes[0] = (bytes[0] + 1).toByte()
            Files.write(renamed, bytes)
            assertEquals(expected, renamed.readLocalContentKey())
            bytes[bytes.lastIndex] = (bytes.last() + 1).toByte()
            Files.write(renamed, bytes)
            assertNotEquals(expected, renamed.readLocalContentKey())
            Files.write(renamed, bytes + byteArrayOf(0))
            assertNotEquals(expected, renamed.readLocalContentKey())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun shortFileUsesWholeFileAndBigEndianSizePrefix() {
        val file = Files.createTempFile("kainos-short-key", ".flac")
        try {
            val bytes = byteArrayOf(1, 2, 3)
            Files.write(file, bytes)
            val payload = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 3, 1, 2, 3)
            val expected = "lc1:" + MessageDigest.getInstance("SHA-256")
                .digest(payload).joinToString("") { "%02x".format(it) }
            assertEquals(expected, file.readLocalContentKey())
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
