package com.universalmusic.player.data.db

import com.universalmusic.player.platform.GuiInstanceLock
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.*
import kotlin.test.*

class GuiInstanceLockTest {
    @Test fun excludesSecondGuiButNotHubOwnerOrOtherDirectory() {
        val root = Files.createTempDirectory("gui-lock")
        try {
            FileChannel.open(Files.createDirectories(root.resolve("home-lan")).resolve("hub.lock"), CREATE, WRITE).use { hub ->
                hub.lock().use {
                    GuiInstanceLock.acquire(root)!!.use {
                        assertNull(GuiInstanceLock.acquire(root))
                        GuiInstanceLock.acquire(root.resolve("other"))!!.close()
                    }
                    GuiInstanceLock.acquire(root)!!.close()
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
