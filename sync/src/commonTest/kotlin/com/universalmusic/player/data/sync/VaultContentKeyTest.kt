package com.universalmusic.player.data.sync

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class VaultContentKeyTest {
    @Test
    fun keysDetectSameSizeConflictsAndOlderPeersKeepSizeFallback() {
        val a = VaultFileEntry("track.flac", 100, 1, contentKey = "lc1:a")
        assertFalse(sameContent(a, a.copy(contentKey = "lc1:b")))
        assertTrue(sameContent(a, a.copy(mtimeMs = 2)))
        assertTrue(sameContent(a, a.copy(contentKey = null)))
        assertFalse(sameContent(a, a.copy(sizeBytes = 200)))
        val plan = planVaultUnion(VaultIndexDocument("a", listOf(a)), VaultIndexDocument("b", listOf(a.copy(contentKey = "lc1:b"))))
        assertEquals(1, plan.conflicts.size)
    }

    @Test
    fun fullHashesDetectDifferencesEvenWhenTailKeysMatch() {
        val a = VaultFileEntry("track.flac", 100, 1, contentHash = "full-a", contentKey = "lc1:tail")
        assertFalse(sameContent(a, a.copy(contentHash = "full-b")))
    }
}
