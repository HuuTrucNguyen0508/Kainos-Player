package com.universalmusic.player.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BorrowedMetadataSessionTest {
    @Test
    fun aRebuildMustNotPublishAPartialSuccessfulRead() = runTest {
        val original = Any()
        var current = original
        var attempts = 0
        val result = retryTransient("test", sleep = {}, isTransient = { it is ReceiverSessionReplaced }) {
            val borrowed = current
            readBorrowedMetadata(borrowed, { current }) {
                if (++attempts == 1) {
                    current = Any()
                    listOf("first") // Remaining per-track reads failed after close and were omitted.
                } else listOf("first", "second")
            }
        }
        assertEquals(listOf("first", "second"), result)
        assertEquals(2, attempts)
    }
}
