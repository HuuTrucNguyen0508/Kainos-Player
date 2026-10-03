package com.universalmusic.player.platform

import android.content.Intent
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URI
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

actual fun createSpotifyWebPlaybackHost(tokenSupplier: SpotifyTokenSupplier): SpotifyWebPlaybackHost =
    AndroidLibrespotPlaybackHost()

actual fun requiresExplicitSpotifyDevice(): Boolean = false

actual suspend fun ensureSpotifyConnectClientAvailable(): Boolean = withContext(Dispatchers.Main) {
    val intent = androidContext.packageManager.getLaunchIntentForPackage("com.spotify.music")
        ?: return@withContext false
    runCatching {
        androidContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

actual fun listenForOAuthRedirect(port: Int, path: String): String {
    error("Android completes OAuth through the universalmusic:// callback, not a localhost server.")
}

actual suspend fun authenticateSpotify(authorizationUrl: String, redirectUri: String): String? {
    val redirect = URI(redirectUri)
    if (redirect.scheme != "http" || redirect.host != "127.0.0.1") {
        openUrl(authorizationUrl)
        return null
    }
    require(redirect.port in 1..65535) { "Spotify redirect must include a valid port" }
    return authenticateSpotifyViaWebView(authorizationUrl, redirectUri)
}

private suspend fun authenticateSpotifyViaWebView(
    authorizationUrl: String,
    redirectUri: String,
): String? {
    val deferred = CompletableDeferred<String?>()
    SpotifyAuthRelay.pending = deferred
    withContext(Dispatchers.Main) {
        val intent = Intent(androidContext, SpotifyAuthActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(SpotifyAuthActivity.EXTRA_AUTH_URL, authorizationUrl)
            putExtra(SpotifyAuthActivity.EXTRA_REDIRECT_URI, redirectUri)
        }
        androidContext.startActivity(intent)
    }
    return withTimeoutOrNull(180_000) { deferred.await() }
}

@Suppress("unused")

private suspend fun authenticateSpotifyViaBrowser(
    authorizationUrl: String,
    redirectUri: String,
): String? {
    val redirect = URI(redirectUri)
    val expectedPath = redirect.rawPath.takeUnless { it.isNullOrBlank() } ?: "/"
    return withContext(Dispatchers.IO) {
        runInterruptible {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), redirect.port))
                server.soTimeout = 1_000
                openUrl(authorizationUrl)
                val deadline = System.currentTimeMillis() + 180_000
                while (System.currentTimeMillis() < deadline) {
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedException("Spotify login was cancelled")
                    }
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    socket.use {
                        it.soTimeout = 5_000
                        val reader = it.getInputStream().bufferedReader()
                        val target = reader.readLine()
                            ?.split(' ')
                            ?.getOrNull(1)
                            ?: return@use
                        var header: String?
                        do {
                            header = reader.readLine()
                        } while (!header.isNullOrEmpty())
                        val request = URI(target)
                        val accepted = request.rawPath == expectedPath
                        val message = if (accepted) {
                            "Authorization received. Return to Kainos Player to finish connecting."
                        } else {
                            "Not found"
                        }
                        val status = if (accepted) "200 OK" else "404 Not Found"
                        val payload = message.encodeToByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".encodeToByteArray())
                            write(payload)
                            flush()
                        }
                        if (accepted) return@runInterruptible "http://127.0.0.1:${redirect.port}$target"
                    }
                }
                error("Spotify login timed out after 3 minutes")
            }
        }
    }
}

actual fun usesLocalOAuthListener(): Boolean = false
