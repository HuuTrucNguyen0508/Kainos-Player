package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PreparedPlayback
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What one gapless mpv process has in its playlist and how far a boundary has progressed. */
internal class GaplessPlaylist {
    var loaded: Boolean = false
    var entryId: Long? = null
    var decodingEntryId: Long? = null
    var crossingEntryId: Long? = null
    val entries: MutableMap<Long, Pair<PreparedPlayback, Long?>> = mutableMapOf()
    var nextDuration: Long? = null
    var crossing: PreparedPlayback? = null
    var crossingRunning: Boolean = false
    var outputDrained: Boolean = false
    var endedAfterCrossing: Boolean = false
    var crossingFailed: Boolean = false
    var awaitingSuccessor: Boolean = false
}

/**
 * Joins the next local file onto the running mpv process. Owns the single prepared successor,
 * binds mpv's playlist entries to it, and decides from mpv's events when audio has really crossed
 * into it. Decoder progress alone is not enough, since the outgoing tail may still be in the
 * output. Mutable state is guarded by the transport lock.
 */
internal class GaplessBoundary(
    private val transport: DesktopTransport,
    private val slot: MpvSlot,
    private val ticker: PositionTicker,
    private val runtime: DesktopPlaybackRuntime,
    private val scope: CoroutineScope,
    /** Drops mpv's lookahead by restarting the outgoing file at the published position. */
    private val restartOwningFileLocked: () -> Unit,
) {
    private var enabled = false
    private var prepared: PreparedPlayback? = null

    fun canJoin(url: String): Boolean = enabled && localFile(url) && runtime.supportsEvents

    fun adoptGenerationLocked(generation: Long, keepPrepared: Boolean) {
        prepared = if (keepPrepared) prepared?.copy(generation = generation) else null
    }

    fun clearPreparedLocked() {
        prepared = null
    }

    /** A seek or teardown that interrupts a boundary still owes the session that successor. */
    fun keepCrossingPreparedLocked(active: ActiveProcess?) {
        if (active?.playlist?.crossing != null) prepared = active.playlist.crossing
    }

    fun keepCrossingPreparedAfterExitLocked(active: ActiveProcess) {
        if (prepared == null) prepared = active.playlist.crossing
    }

    /** A freshly started process has no playlist yet; give it the successor it should have. */
    fun reprepareLocked() {
        prepared?.takeIf { enabled && it.generation == transport.activeGeneration }?.let(::prepareNext)
    }

    fun configure(enabled: Boolean) {
        synchronized(transport.lock) { this.enabled = enabled }
        if (!enabled) prepareNext(null)
    }

    fun prepareNext(next: PreparedPlayback?) {
        synchronized(transport.lock) {
            val candidate = next?.takeIf { enabled && it.generation == transport.activeGeneration && localFile(it.handle.url) }
            prepared = candidate
            val active = slot.active ?: return
            if (!active.gapless) return
            val playlist = active.playlist
            if (playlist.crossing != null && candidate !== playlist.crossing) {
                restartOwningFileLocked()
                return
            }
            prepared = candidate
            slot.enqueueLocked {
                if (!slot.isActive(active)) return@enqueueLocked
                // Remove the old successor before probing a potentially incompatible replacement.
                runtime.sendIpc("""["playlist-clear"]""", active.socket)
                val currentUrl = synchronized(transport.lock) { (transport.handle as? PlaybackHandle.Url)?.url }
                // File probes must not hold up pause, seek, or other socket commands.
                scope.launch(Dispatchers.IO) {
                    val compatible = runInterruptible { candidate == null || (currentUrl != null && runtime.canPrepare(currentUrl, candidate.handle.url)) }
                    val duration = if (candidate != null && compatible) runInterruptible { runtime.probeDurationMs(candidate.handle.url) } else null
                    synchronized(transport.lock) {
                        if (prepared !== candidate || slot.active !== active) return@launch
                        slot.enqueueLocked apply@{
                            if (!synchronized(transport.lock) { prepared === candidate && slot.active === active }) return@apply
                            if (!compatible) {
                                synchronized(transport.lock) { if (prepared === candidate) prepared = null }
                                runtime.sendIpc("""["set_property","gapless-audio","no"]""", active.socket)
                                synchronized(transport.lock) {
                                    if (slot.active === active && playlist.awaitingSuccessor) {
                                        playlist.awaitingSuccessor = false
                                        transport.publish(transport.current.copy(status = EngineStatus.ENDED))
                                    }
                                }
                                return@apply
                            }
                            runtime.sendIpc("""["set_property","gapless-audio","${if (candidate == null) "no" else "yes"}"]""", active.socket)
                            if (candidate == null) synchronized(transport.lock) {
                                if (slot.active === active && playlist.awaitingSuccessor) {
                                    playlist.awaitingSuccessor = false
                                    transport.publish(transport.current.copy(status = EngineStatus.ENDED))
                                }
                            }
                            if (candidate != null) {
                                synchronized(transport.lock) {
                                    if (prepared !== candidate || slot.active !== active) return@apply
                                    playlist.nextDuration = duration
                                }
                                val id = runtime.loadEntry("""["loadfile",${Json.encodeToString(candidate.handle.url)},"append-play"]""", active.socket)
                                synchronized(transport.lock) {
                                    if (id != null) playlist.entries[id] = candidate to duration
                                    else if (prepared === candidate) prepared = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun localFile(url: String): Boolean = url.startsWith("file:") || !url.contains("://")

    /** Runs in the IPC FIFO. With no successor, EOF can only enter idle, never another file. */
    fun seek(active: ActiveProcess, handle: PlaybackHandle.Url, seconds: Double): Boolean {
        val playlist = active.playlist
        if (!slot.isActive(active)) return false
        val entries = runCatching {
            Json.parseToJsonElement(runtime.readProperty("playlist", active.socket) ?: return false)
                as kotlinx.serialization.json.JsonArray
        }.getOrNull() ?: return false
        // mpv accepts playlist indices, not IDs. Resolve the bound IDs from this snapshot and
        // remove backwards so earlier indices remain stable. Automatic advance retains indices.
        var index = entries.size - 1
        while (index >= 0) {
            val id = entries[index].jsonObject["id"]?.jsonPrimitive?.content?.toLongOrNull()
            val successor = synchronized(transport.lock) { id in playlist.entries }
            if (successor && !runtime.sendIpc("""["playlist-remove",$index]""", active.socket)) return false
            index--
        }
        val playing = runCatching {
            (Json.parseToJsonElement(runtime.readProperty("playlist", active.socket) ?: return false)
                as kotlinx.serialization.json.JsonArray).firstOrNull {
                it.jsonObject["playing"]?.jsonPrimitive?.content == "true"
            }?.jsonObject?.get("id")?.jsonPrimitive?.content?.toLongOrNull()
        }.getOrNull()
        val ownsFile = synchronized(transport.lock) {
            slot.active === active && transport.handle === handle && playlist.crossing == null &&
                playing != null && playing == playlist.entryId
        }
        if (!ownsFile || !runtime.sendIpc("""["set_property","time-pos",$seconds]""", active.socket)) return false
        synchronized(transport.lock) {
            if (slot.active !== active || transport.handle !== handle) return true
            playlist.entries.clear()
            playlist.awaitingSuccessor = false
            prepared?.let { prepareNext(it) }
        }
        return true
    }

    fun onEvent(active: ActiveProcess, line: String) {
        val event = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return
        val playlist = active.playlist
        synchronized(transport.lock) {
            if (slot.active !== active) return
            when (event["event"]?.jsonPrimitive?.content) {
                "idle" -> playlist.outputDrained = true
                "start-file" -> {
                    playlist.outputDrained = false
                    playlist.decodingEntryId = event["playlist_entry_id"]?.jsonPrimitive?.content?.toLongOrNull()
                }
                "file-loaded" -> {
                    if (!playlist.loaded && (playlist.decodingEntryId == null || playlist.decodingEntryId == playlist.entryId)) {
                        playlist.loaded = true
                        return
                    }
                    val binding = playlist.entries[playlist.decodingEntryId]
                    val next = binding?.first?.takeIf { it === prepared } ?: run {
                        // Cancellation raced an already decoded file. Let the session select the
                        // current successor again rather than playing an unowned queue item.
                        ticker.cancelLocked()
                        slot.stopLocked()
                        transport.publish(transport.current.copy(status = EngineStatus.ENDED))
                        return
                    }
                    if (next.generation != transport.activeGeneration) return
                    prepared = null
                    playlist.awaitingSuccessor = false
                    playlist.crossing = next
                    playlist.crossingEntryId = playlist.decodingEntryId
                    playlist.nextDuration = binding.second
                }
                "playback-restart" -> {
                    val next = playlist.crossing ?: return
                    if (playlist.crossingRunning) return
                    playlist.crossingRunning = true
                    awaitOutputCrossing(active, next)
                }
                "end-file" -> {
                    val reason = event["reason"]?.jsonPrimitive?.content
                    val id = event["playlist_entry_id"]?.jsonPrimitive?.content?.toLongOrNull()
                    if (id != null && id != playlist.entryId && id != playlist.crossingEntryId && id !in playlist.entries) return
                    if (reason != "eof" && reason != "error") return
                    if (playlist.crossing != null && (id == null || id == playlist.crossingEntryId)) {
                        playlist.endedAfterCrossing = true
                        playlist.crossingFailed = reason == "error"
                        return
                    }
                    if (reason == "eof" && prepared != null && (id == null || id == playlist.entryId)) {
                        playlist.awaitingSuccessor = true
                        return
                    }
                    if (reason == "error" && playlist.awaitingSuccessor && (id == null || id != playlist.entryId)) {
                        playlist.awaitingSuccessor = false
                        prepared = null
                        val generation = transport.activeGeneration
                        // The outgoing decoder reached EOF but its tail may still be in the AO.
                        // A broken successor must not cause session teardown to discard that tail.
                        drainOutgoingTail(active, generation)
                        return
                    }
                    // Errors on the outgoing entry must also cancel any pending boundary.
                    playlist.crossing = null
                    playlist.crossingRunning = false
                    prepared = null
                    ticker.cancelLocked()
                    transport.publish(transport.current.copy(status = if (reason == "error")
                        EngineStatus.FAILED else EngineStatus.ENDED, error = if (reason == "error")
                        "Local file playback failed" else null))
                }
            }
        }
    }

    private fun awaitOutputCrossing(active: ActiveProcess, next: PreparedPlayback) {
        val playlist = active.playlist
        scope.launch {
            // file-loaded means decoding changed, possibly while the previous audio
            // is still buffered. audio-pts includes the output driver delay.
            var unresolvedMs = 0L
            var lastSample = System.nanoTime()
            while (true) {
                val pts = slot.awaitIpc { runtime.readProperty("audio-pts", active.socket)?.toDoubleOrNull() }
                val now = System.nanoTime()
                val crossed = synchronized(transport.lock) {
                    if (slot.active !== active || playlist.crossing !== next) return@launch
                    if (!transport.userPaused) unresolvedMs += (now - lastSample) / 1_000_000
                    playlist.outputDrained || (pts != null && pts.isFinite() && pts >= 0)
                }
                if (crossed) break
                if (unresolvedMs >= 2_000) {
                    synchronized(transport.lock) {
                        if (slot.active !== active || playlist.crossing !== next) return@launch
                        // No output evidence: discard lookahead and let the session
                        // resolve the selected successor normally, without claiming it played.
                        slot.stopLocked()
                        ticker.cancelLocked()
                        transport.publish(transport.current.copy(status = EngineStatus.ENDED))
                    }
                    return@launch
                }
                lastSample = now
                delay(10)
            }
            synchronized(transport.lock) {
                if (slot.active !== active || playlist.crossing !== next || next.generation != transport.activeGeneration) return@synchronized
                playlist.entryId = playlist.crossingEntryId
                playlist.entries.remove(playlist.entryId)
                playlist.crossing = null
                playlist.crossingRunning = false
                val previous = transport.activeGeneration++
                transport.handle = next.handle
                transport.quality = next.quality
                ticker.anchor(0)
                slot.epoch++
                transport.publish(EngineState(status = if (playlist.crossingFailed) EngineStatus.FAILED else if (playlist.endedAfterCrossing) EngineStatus.ENDED else
                    if (transport.userPaused) EngineStatus.PAUSED else EngineStatus.PLAYING,
                    error = if (playlist.crossingFailed) "Local file playback failed" else null,
                    durationMs = playlist.nextDuration, seamlessItemId = next.itemId, previousGeneration = previous))
                playlist.endedAfterCrossing = false
                playlist.crossingFailed = false
            }
        }
    }

    private fun drainOutgoingTail(active: ActiveProcess, generation: Long) {
        scope.launch {
            var remaining = (transport.current.durationMs?.minus(transport.current.positionMs) ?: 0).coerceAtLeast(0)
            var last = System.nanoTime()
            while (remaining > 0) {
                delay(10)
                val now = System.nanoTime()
                val valid = synchronized(transport.lock) {
                    if (!transport.userPaused) remaining -= (now - last) / 1_000_000
                    slot.active === active && transport.activeGeneration == generation
                }
                if (!valid) return@launch
                last = now
            }
            synchronized(transport.lock) {
                if (slot.active === active && transport.activeGeneration == generation) {
                    ticker.cancelLocked()
                    transport.publish(transport.current.copy(status = EngineStatus.ENDED))
                }
            }
        }
    }
}
