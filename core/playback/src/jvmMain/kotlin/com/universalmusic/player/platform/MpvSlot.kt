package com.universalmusic.player.platform

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch

/**
 * The one mpv process the engine may talk to, and the FIFO that orders every socket call to it.
 * Mutable members are guarded by [lock].
 */
internal class MpvSlot(
    private val runtime: DesktopPlaybackRuntime,
    private val lock: Any,
    scope: CoroutineScope,
) {
    var active: ActiveProcess? = null

    /**
     * Bumped (under [lock]) by every local mpv transport decision: play / stop / pause /
     * resume / seek. Async IPC results captured under an older epoch are stale and must not publish.
     */
    var epoch = 0L

    /**
     * Single-consumer FIFO for all mpv socket I/O. Jobs are enqueued while holding [lock]
     * so queue order matches the order of lock-protected transport decisions; the jobs themselves
     * run on Dispatchers.IO and never hold the lock across socket I/O.
     */
    private val jobs = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (job in jobs) {
                try {
                    job()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // A failed IPC job must not stop the FIFO consumer.
                }
            }
        }
    }

    /** Must be called with [lock] held so FIFO order matches transport decision order. */
    fun enqueueLocked(job: suspend () -> Unit) {
        jobs.trySend(job)
    }

    /** Suspends (without holding the lock) until [block] has run in FIFO order on the IPC worker. */
    suspend fun <T> awaitIpc(block: () -> T): T {
        val result = CompletableDeferred<T>()
        synchronized(lock) {
            enqueueLocked { result.completeWith(runCatching(block)) }
        }
        return result.await()
    }

    fun isActive(process: ActiveProcess): Boolean =
        synchronized(lock) { active === process }

    /** Socket I/O for [process]; skipped (false, like a failed IPC) once it is no longer current. */
    fun sendIfActive(process: ActiveProcess, commandArrayJson: String): Boolean =
        isActive(process) && runtime.sendIpc(commandArrayJson, process.socket)

    fun stopLocked(): Boolean {
        val process = active ?: return true
        runCatching { process.events?.close() }
        val terminated = try {
            process.process.destroyForcibly()
            process.process.waitFor(1, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }
        if (!terminated) return false
        if (active === process) active = null
        runtime.deleteIpcPath(process.socket)
        return true
    }
}

internal class ActiveProcess(
    val process: Process,
    val socket: Path,
    val gapless: Boolean = false,
    var events: MpvEventStream? = null,
    val playlist: GaplessPlaylist = GaplessPlaylist(),
)
