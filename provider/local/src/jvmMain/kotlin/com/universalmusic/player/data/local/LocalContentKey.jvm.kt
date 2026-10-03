package com.universalmusic.player.data.local

import java.io.RandomAccessFile
import java.nio.file.Path
import java.security.MessageDigest

actual fun createLocalContentKeyReader(): LocalContentKeyReader? = null

fun Path.readLocalContentKey(): String? = runCatching {
    RandomAccessFile(toFile(), "r").use { file ->
        val size = file.length()
        val tail = ByteArray(minOf(size, LOCAL_CONTENT_TAIL_BYTES.toLong()).toInt())
        file.seek(size - tail.size)
        file.readFully(tail)
        if (file.length() != size) return@use null
        "lc1:" + MessageDigest.getInstance("SHA-256")
            .digest(localContentKeyPayload(size, tail)).joinToString("") { "%02x".format(it) }
    }
}.getOrNull()
