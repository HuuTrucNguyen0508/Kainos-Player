package com.universalmusic.player.platform

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketException
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Waits before each retry of a librespot metadata read. Phone traces (2026-11-03 to 11-09) show a
 * Spotify access point refusing or resetting one connect and accepting another seconds later.
 */
internal val LIBRESPOT_METADATA_BACKOFF_MS = listOf(1_000L, 3_000L, 8_000L)

/**
 * Runs [attempt] until it returns, fails in a way [isTransient] rejects, or [backoffMs] runs out.
 * Each attempt starts over, so it must acquire its own session.
 */
internal suspend fun <T> retryTransient(
    label: String,
    backoffMs: List<Long> = LIBRESPOT_METADATA_BACKOFF_MS,
    isTransient: (Throwable) -> Boolean = ::isTransientNetworkFailure,
    sleep: suspend (Long) -> Unit = { delay(it) },
    attempt: suspend () -> T,
): T {
    var retries = 0
    while (true) {
        try {
            return attempt()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (retries >= backoffMs.size || !isTransient(failure)) throw failure
            val waitMs = backoffMs[retries++]
            PlaybackTrace.log("Librespot", "$label attempt $retries failed ($failure); retrying in $waitMs ms")
            sleep(waitMs)
        }
    }
}

/** Refused or reset connections, timeouts and DNS failures: what a weak or changing mobile link produces. */
internal fun isTransientNetworkFailure(failure: Throwable): Boolean =
    generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }.take(8).any {
        it is SocketException || it is InterruptedIOException || it is UnknownHostException || it is EOFException
    }
