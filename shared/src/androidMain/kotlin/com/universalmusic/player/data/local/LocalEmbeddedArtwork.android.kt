package com.universalmusic.player.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.universalmusic.player.platform.androidContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createLocalEmbeddedArtworkExtractor(): LocalEmbeddedArtworkExtractor? =
    AndroidEmbeddedArtworkExtractor(androidContext)

/**
 * Extracts embedded pictures with [MediaMetadataRetriever] and stores them under
 * `meta-cache/local-art/<sha256(trackId)>.jpg`. A `.none` marker records files without art so
 * they are probed once, not on every launch. Lives inside `meta-cache` on purpose: Settings →
 * Clear metadata & artwork cache wipes it and the next play re-extracts.
 */
internal class AndroidEmbeddedArtworkExtractor(
    context: Context,
) : LocalEmbeddedArtworkExtractor {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "meta-cache/local-art")

    override fun cachedArtworkUri(trackId: String): String? {
        val file = artFile(trackId)
        return if (file.isFile && file.length() > 0L) file.toURI().toASCIIString() else null
    }

    override suspend fun extractArtworkUri(trackId: String, location: String): String? {
        cachedArtworkUri(trackId)?.let { return it }
        if (noneMarker(trackId).exists()) return null
        return withContext(Dispatchers.IO) {
            val picture = readEmbeddedPicture(location)
            dir.mkdirs()
            if (picture == null) {
                runCatching { noneMarker(trackId).createNewFile() }
                return@withContext null
            }
            val bytes = normalize(picture) ?: run {
                runCatching { noneMarker(trackId).createNewFile() }
                return@withContext null
            }
            val target = artFile(trackId)
            val tmp = File(dir, "${target.name}.tmp")
            runCatching {
                tmp.writeBytes(bytes)
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                target.toURI().toASCIIString()
            }.onFailure { Log.w(TAG, "write failed for $trackId: ${it.message}") }.getOrNull()
        }
    }

    private fun readEmbeddedPicture(location: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, Uri.parse(location))
            retriever.embeddedPicture
        } catch (failure: Throwable) {
            Log.w(TAG, "retriever failed for $location: ${failure.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Hi-res covers in FLACs can be several MB. Anything larger than [MAX_EDGE_PX] or
     * [LocalArtworkPolicy.MAX_ARTWORK_BYTES] is re-encoded as JPEG; smaller images keep
     * their original bytes.
     */
    private fun normalize(picture: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(picture, 0, picture.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= MAX_EDGE_PX && picture.size <= LocalArtworkPolicy.MAX_ARTWORK_BYTES) return picture
        var sample = 1
        while (longest / (sample * 2) >= MAX_EDGE_PX) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            picture, 0, picture.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val out = ByteArrayOutputStream()
        decoded.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        decoded.recycle()
        return out.toByteArray()
    }

    private fun artFile(trackId: String) = File(dir, "${key(trackId)}.jpg")

    private fun noneMarker(trackId: String) = File(dir, "${key(trackId)}.none")

    private fun key(trackId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(trackId.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "KainosArtwork"
        const val MAX_EDGE_PX = 1024
        const val JPEG_QUALITY = 90
    }
}
