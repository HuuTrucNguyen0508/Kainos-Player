package com.universalmusic.player.platform

import java.io.IOException
import java.util.concurrent.CompletableFuture
import kotlin.concurrent.thread
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeoutOrNull

/** Matches [isRetryableLibrespotConnectFailure] through "timed out", so the next attempt starts. */
internal class ApConnectTimeoutException(deadlineMs: Long) :
    IOException("AP connect timed out after $deadlineMs ms")

/**
 * Runs a blocking librespot connect on its own thread and stops waiting for it after
 * [deadlineMs] or when the caller is cancelled. librespot opens the AP socket with no connect or
 * read timeout, so on a VPN over mobile data one attempt hung for 297 s and held back every
 * Spotify start (phone trace of 2026-11-24 13:11). A given-up connect keeps its thread until the
 * socket fails; if it answers after all, [discardLate] gets the result, exactly once.
 */
internal suspend fun <T : Any> connectWithin(
    deadlineMs: Long,
    threadName: String,
    discardLate: (T) -> Unit,
    connect: () -> T,
): T {
    val attempt = CompletableFuture<T>()
    thread(name = threadName, isDaemon = true) {
        try {
            val value = connect()
            if (!attempt.complete(value)) runCatching { discardLate(value) }
        } catch (failure: Throwable) {
            attempt.completeExceptionally(failure)
        }
    }
    var answered = false
    try {
        val value = withTimeoutOrNull(deadlineMs) { attempt.await() }
            ?: throw ApConnectTimeoutException(deadlineMs)
        answered = true
        return value
    } finally {
        // Whichever side loses this race owns a value that nobody will use.
        if (!answered && !attempt.cancel(false)) {
            runCatching { attempt.getNow(null) }.getOrNull()?.let { runCatching { discardLate(it) } }
        }
    }
}
