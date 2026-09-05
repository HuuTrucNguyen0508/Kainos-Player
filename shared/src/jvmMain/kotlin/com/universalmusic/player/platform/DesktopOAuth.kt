package com.universalmusic.player.platform

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.queryString
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.util.concurrent.CompletableFuture

internal fun awaitOAuthRedirect(port: Int, path: String): String {
    val latch = CompletableFuture<String>()
    val server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
        routing {
            get(path) {
                val query = call.request.queryString()
                val uri = "http://127.0.0.1:$port$path" + if (query.isBlank()) "" else "?$query"
                call.respondText("Connected. You can return to Kainos Player.")
                latch.complete(uri)
            }
        }
    }
    server.start(wait = false)
    return try {
        latch.get()
    } finally {
        server.stop(200, 400)
    }
}
