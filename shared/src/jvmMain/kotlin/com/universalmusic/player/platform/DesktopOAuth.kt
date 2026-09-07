package com.universalmusic.player.platform

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext

internal fun awaitOAuthRedirect(port: Int, path: String): String {
    return awaitOAuthRedirect(port, path) {}
}

private fun awaitOAuthRedirect(port: Int, path: String, listenerReady: () -> Unit): String {
    val latch = CompletableFuture<String>()
    val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
        routing {
            get(path) {
                val query = call.request.queryString()
                val uri = "http://127.0.0.1:$port$path" + if (query.isBlank()) "" else "?$query"
                call.respondText("Authorization received. Return to Kainos Player to finish connecting.")
                latch.complete(uri)
            }
        }
    }
    server.start(wait = false)
    return try {
        listenerReady()
        try {
            latch.get(3, TimeUnit.MINUTES)
        } catch (_: TimeoutException) {
            error("Spotify login timed out after 3 minutes")
        }
    } finally {
        server.stop(200, 400)
    }
}

internal suspend fun authenticateWithLoopbackServer(
    authorizationUrl: String,
    redirectUri: String,
): String = withContext(Dispatchers.IO) {
    val redirect = URI(redirectUri)
    require(redirect.scheme == "http" && redirect.host == "127.0.0.1") {
        "Spotify desktop redirect must use http://127.0.0.1"
    }
    require(redirect.port in 1..65535) { "Spotify desktop redirect must include a valid port" }
    val path = redirect.rawPath.takeUnless { it.isNullOrBlank() } ?: "/"
    runInterruptible {
        awaitOAuthRedirect(redirect.port, path) { openUrl(authorizationUrl) }
    }
}
