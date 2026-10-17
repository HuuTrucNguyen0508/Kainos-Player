package com.universalmusic.player.data.sync

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import kotlin.math.min

class HubOwnerLock private constructor(
    private val path: Path,
    private val channel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    override fun close() = synchronized(channels) {
        if (channels[path] !== channel) return@synchronized
        try {
            lock.release()
        } finally {
            closeChannel(path, channel)
        }
    }

    companion object {
        private val channels = mutableMapOf<Path, FileChannel>()

        fun acquire(dataDir: Path, timeoutMs: Long): HubOwnerLock? {
            require(timeoutMs >= 0)
            val directory = dataDir.resolve("home-lan")
            Files.createDirectories(directory)
            val path = directory.toRealPath().resolve("hub.lock")
            val deadline = System.nanoTime() + timeoutMs * 1_000_000
            var channel: FileChannel? = null
            try {
                do {
                    if (channel == null) channel = openChannel(path)
                    val lock = channel?.tryLock()
                    if (lock != null) return HubOwnerLock(path, checkNotNull(channel), lock)
                    val remaining = deadline - System.nanoTime()
                    if (remaining <= 0) break
                    Thread.sleep(min(25L, (remaining / 1_000_000).coerceAtLeast(1)))
                } while (true)
                channel?.let { closeChannel(path, it) }
                return null
            } catch (error: Throwable) {
                channel?.let { closeChannel(path, it) }
                throw error
            }
        }

        private fun openChannel(path: Path): FileChannel? = synchronized(channels) {
            if (path in channels) return@synchronized null
            FileChannel.open(path, CREATE, WRITE).also { channels[path] = it }
        }

        private fun closeChannel(path: Path, channel: FileChannel) = synchronized(channels) {
            try {
                channel.close()
            } finally {
                channels.remove(path)
            }
        }
    }
}
