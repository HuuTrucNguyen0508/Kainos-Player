package com.universalmusic.player.platform

import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LibrespotEventBridgeTest {
    @Test fun readerOwnsItsJournalBeforeItsWorkerStarts() = runTest {
        val bridge = LibrespotEventBridge(StandardTestDispatcher(testScheduler)) {}
        try {
            // Shutdown can unlink the journal before the IO worker starts opening it.
            Files.delete(bridge.program.parent.resolve("events"))
            runCurrent()
            assertTrue(bridge.isOpen, "The reader must already own the journal descriptor")
        } finally {
            bridge.close()
            runCurrent()
        }
    }
}
