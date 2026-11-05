package com.universalmusic.player.ui.theme

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.WatchService
import java.nio.file.WatchKey
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal class StateFileWatcher<T>(
    path: Path,
    scope: CoroutineScope,
    private val debounceMs: Long = 150,
    private val backstopMs: Long = 5_000,
    private val watchServiceFactory: () -> WatchService = { path.fileSystem.newWatchService() },
    private val parse: (String?) -> T?,
) {
    private val path = path.toAbsolutePath().normalize()
    private val mutableState = MutableStateFlow(parse(null))
    val state = mutableState.asStateFlow()
    @Volatile internal var isClosed = false
        private set

    private data class Stamp(val size: Long, val modified: FileTime)

    init {
        scope.launch(Dispatchers.IO) {
            val watch = try { watchServiceFactory() } catch (_: IOException) { null }
            var stamp: Stamp? = null
            var key: WatchKey? = null
            var watched: Path? = null
            fun refresh() {
                try {
                    if (!Files.exists(this@StateFileWatcher.path)) {
                        stamp = null
                        mutableState.value = parse(null)
                        return
                    }
                    val attrs = Files.readAttributes(this@StateFileWatcher.path, BasicFileAttributes::class.java)
                    val next = Stamp(attrs.size(), attrs.lastModifiedTime())
                    if (next == stamp) return
                    val text = Files.readString(this@StateFileWatcher.path)
                    stamp = next
                    parse(text)?.let { mutableState.value = it }
                } catch (_: IOException) {
                    // A replacement may briefly be unreadable; keep the last valid value and retry.
                }
            }
            try {
                refresh()
                var readAt = Long.MAX_VALUE
                var pollAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(backstopMs)
                while (isActive) {
                    var directory = this@StateFileWatcher.path.parent
                    while (directory != null && !Files.isDirectory(directory)) directory = directory.parent
                    if (directory != watched || key?.isValid != true) {
                        key?.cancel()
                        key = null
                        watched = null
                        if (directory != null && watch != null) {
                            try {
                                key = directory.register(watch, ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE)
                                watched = directory
                                refresh()
                            } catch (_: IOException) {
                                // The shell can remove the directory between discovery and registration.
                            }
                        }
                    }
                    val now = System.nanoTime()
                    val waitMs = TimeUnit.NANOSECONDS.toMillis(minOf(readAt, pollAt) - now).coerceAtLeast(1)
                    val eventKey = if (watch == null) {
                        delay(waitMs)
                        null
                    } else runInterruptible { watch.poll(waitMs, TimeUnit.MILLISECONDS) }
                    if (eventKey != null) {
                        val relevant = eventKey.pollEvents().any { event ->
                            event.kind() == java.nio.file.StandardWatchEventKinds.OVERFLOW ||
                                watched?.resolve(event.context().toString())?.let { changed ->
                                    changed == this@StateFileWatcher.path ||
                                        this@StateFileWatcher.path.startsWith(changed)
                                } == true
                        }
                        if (!eventKey.reset() || relevant) readAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(debounceMs)
                    }
                    val after = System.nanoTime()
                    if (after >= readAt || after >= pollAt) {
                        refresh()
                        readAt = Long.MAX_VALUE
                        pollAt = after + TimeUnit.MILLISECONDS.toNanos(backstopMs)
                    }
                }
            } finally {
                watch?.close()
                isClosed = true
            }
        }
    }
}
