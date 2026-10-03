package com.universalmusic.player.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlin.math.max

/** Loads bounded artwork bytes suitable for MediaMetadata.artworkData. */
internal class AndroidMediaArtworkLoader(
    context: Context,
) {
    private val applicationContext = context.applicationContext
    private val cache = object : LruCache<String, ByteArray>(MAX_CACHE_KIB) {
        override fun sizeOf(key: String, value: ByteArray): Int = max(1, value.size / 1024)
    }

    suspend fun load(url: String): ByteArray? = withContext(Dispatchers.IO) {
        cache.get(url)?.let { return@withContext it }
        coroutineContext.ensureActive()
        val source = runCatching { open(url)?.use { it.readBounded(MAX_SOURCE_BYTES) } }.getOrNull()
            ?: return@withContext null
        coroutineContext.ensureActive()
        val scaled = scaleForSession(source) ?: return@withContext null
        cache.put(url, scaled)
        scaled
    }

    private fun open(value: String): InputStream? {
        val uri = Uri.parse(value)
        return when (uri.scheme?.lowercase()) {
            "content", "file", "android.resource" ->
                applicationContext.contentResolver.openInputStream(uri)
            "http", "https" -> {
                val connection = URL(value).openConnection() as HttpURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.inputStream
            }
            null -> File(value).takeIf(File::isFile)?.inputStream()
            else -> null
        }
    }

    private fun scaleForSession(source: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (max(bounds.outWidth / sampleSize, bounds.outHeight / sampleSize) > MAX_DIMENSION * 2) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            source,
            0,
            source.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: return null
        val largest = max(decoded.width, decoded.height)
        val scaled = if (largest > MAX_DIMENSION) {
            val ratio = MAX_DIMENSION.toFloat() / largest.toFloat()
            Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(1),
                (decoded.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            decoded
        }

        return try {
            ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) return null
                output.toByteArray().takeIf { it.isNotEmpty() && it.size <= MAX_SESSION_BYTES }
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }

    private fun InputStream.readBounded(limit: Int): ByteArray? {
        val output = ByteArrayOutputStream(minOf(limit, DEFAULT_BUFFER_SIZE * 8))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) return null
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        const val MAX_DIMENSION = 768
        const val MAX_SOURCE_BYTES = 2 * 1024 * 1024
        const val MAX_SESSION_BYTES = 1024 * 1024
        const val MAX_CACHE_KIB = 8 * 1024
        const val JPEG_QUALITY = 90
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 12_000
    }
}
