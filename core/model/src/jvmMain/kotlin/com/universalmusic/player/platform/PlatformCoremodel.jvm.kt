package com.universalmusic.player.platform

import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun isNetworkAvailable(): Boolean = true

actual fun monotonicElapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L

actual fun sha256Bytes(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(SecureRandom()::nextBytes)

actual fun encodeUrl(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

actual fun openUrl(url: String) {
    val openedWithDesktop = Desktop.isDesktopSupported() && runCatching {
        Desktop.getDesktop().browse(URI(url))
    }.isSuccess
    if (!openedWithDesktop) {
        runCatching { ProcessBuilder("xdg-open", url).start() }
            .getOrElse { error("Could not open the system browser: ${it.message}") }
    }
}

actual fun platformLabel(): String = "Linux"
