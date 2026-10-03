package com.universalmusic.player.data.local

import android.net.Uri
import com.universalmusic.player.platform.androidContext
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createLocalContentKeyReader(): LocalContentKeyReader? = LocalContentKeyReader { location ->
    withContext(Dispatchers.IO) {
        runCatching {
            androidContext.contentResolver.openFileDescriptor(Uri.parse(location), "r")?.use { descriptor ->
                FileInputStream(descriptor.fileDescriptor).use inputUse@ { input ->
                    val channel = input.channel
                    val size = channel.size()
                    val tail = ByteArray(minOf(size, LOCAL_CONTENT_TAIL_BYTES.toLong()).toInt())
                    channel.position(size - tail.size)
                    val buffer = ByteBuffer.wrap(tail)
                    while (buffer.hasRemaining()) {
                        check(channel.read(buffer) > 0) { "File changed while reading content key" }
                    }
                    if (channel.size() != size) return@inputUse null
                    "lc1:" + MessageDigest.getInstance("SHA-256")
                        .digest(localContentKeyPayload(size, tail)).joinToString("") { "%02x".format(it) }
                }
            }
        }.getOrNull()
    }
}
