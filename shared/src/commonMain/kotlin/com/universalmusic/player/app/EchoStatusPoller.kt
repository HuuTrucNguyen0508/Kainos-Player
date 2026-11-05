package com.universalmusic.player.app

import com.universalmusic.player.domain.echo.EchoStatus
import com.universalmusic.player.domain.echo.EchoStatusSource
import com.universalmusic.player.domain.echo.parseEchoStatus
import com.universalmusic.player.platform.currentTimeMillis
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout

/** Base URL of the Echo gateway's status port, or null where this platform has no source yet. */
internal expect fun echoStatusBaseUrl(): String?

internal const val ECHO_POLL_MS = 5_000L
internal const val ECHO_BACKOFF_MS = 30_000L
private const val ECHO_REQUEST_TIMEOUT_MS = 3_000L

/**
 * Polls the gateway only while someone collects [status] (Home on screen). One missed reply keeps
 * the last status; a second clears it and slows polling down until the gateway answers again.
 */
internal class EchoStatusPoller(
    scope: CoroutineScope,
    private val fetch: suspend () -> String,
    private val clock: () -> Long = ::currentTimeMillis,
) : EchoStatusSource {
    override val status: StateFlow<EchoStatus?> = flow {
        var last: EchoStatus? = null
        var misses = 0
        while (true) {
            val reply = try {
                parseEchoStatus(fetch(), clock())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            misses = if (reply == null) misses + 1 else 0
            if (reply != null || misses > 1) last = reply
            emit(last)
            delay(if (misses > 1) ECHO_BACKOFF_MS else ECHO_POLL_MS)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(), null)

    companion object {
        fun http(scope: CoroutineScope, http: HttpClient, baseUrl: String): EchoStatusPoller =
            EchoStatusPoller(scope, fetch = {
                withTimeout(ECHO_REQUEST_TIMEOUT_MS) {
                    val response = http.get("${baseUrl.trimEnd('/')}/now-playing")
                    check(response.status.isSuccess()) { "Echo gateway answered ${response.status}" }
                    response.bodyAsText()
                }
            })
    }
}
