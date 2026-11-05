package com.universalmusic.player.platform

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Separate from hub ownership: a hub-only process must never exclude a GUI. */
class GuiInstanceLock private constructor(private val channel: FileChannel, private val lock: FileLock) : AutoCloseable {
    override fun close() {
        try { lock.release() } finally { channel.close() }
    }
    companion object {
        fun acquire(dataDir: Path): GuiInstanceLock? {
            Files.createDirectories(dataDir)
            val channel = FileChannel.open(dataDir.resolve("gui.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) { channel.close(); return null }
            return GuiInstanceLock(channel, lock)
        }
    }
}
