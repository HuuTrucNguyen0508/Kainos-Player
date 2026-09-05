package com.universalmusic.player.data.local

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class MediaStoreLocalTrackSource(
    context: Context,
) : LocalTrackSource {
    private val contentResolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun scan(): List<LocalTrack> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        contentResolver.query(
            collection,
            projection(),
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} ASC",
        )?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    cursor.toLocalTrack(collection)?.let(::add)
                }
            }
        }.orEmpty()
    }

    private fun projection(): Array<String> = buildList {
        add(MediaStore.Audio.Media._ID)
        add(MediaStore.Audio.Media.TITLE)
        add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.ARTIST)
        add(MediaStore.Audio.Media.ALBUM)
        add(MediaStore.Audio.Media.ALBUM_ID)
        add(MediaStore.Audio.Media.DURATION)
        add(MediaStore.Audio.Media.MIME_TYPE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            add(BITRATE_COLUMN)
        }
    }.toTypedArray()

    private fun Cursor.toLocalTrack(collection: Uri): LocalTrack? {
        val mediaId = longOrNull(MediaStore.Audio.Media._ID) ?: return null
        val contentUri = ContentUris.withAppendedId(collection, mediaId).toString()
        val title = textOrNull(MediaStore.Audio.Media.TITLE)
            ?: textOrNull(MediaStore.Audio.Media.DISPLAY_NAME)?.withoutFileExtension()
            ?: "Unknown track"
        val artist = textOrNull(MediaStore.Audio.Media.ARTIST)
        val albumId = positiveLongOrNull(MediaStore.Audio.Media.ALBUM_ID)
        val mimeType = textOrNull(MediaStore.Audio.Media.MIME_TYPE)
        val bitrateKbps = positiveLongOrNull(BITRATE_COLUMN)
            ?.div(1_000L)
            ?.takeIf { it > 0L }
            ?.coerceAtMost(Int.MAX_VALUE.toLong())
            ?.toInt()

        return LocalTrack(
            id = mediaId.toString(),
            title = title,
            artists = artist?.let(::listOf).orEmpty(),
            album = textOrNull(MediaStore.Audio.Media.ALBUM),
            durationMs = positiveLongOrNull(MediaStore.Audio.Media.DURATION),
            artworkUri = albumId?.let { ContentUris.withAppendedId(ALBUM_ART_URI, it).toString() },
            location = contentUri,
            quality = audioQuality(mimeType, bitrateKbps),
        )
    }

    private fun Cursor.textOrNull(columnName: String): String? {
        val index = getColumnIndex(columnName)
        if (index < 0 || isNull(index)) return null
        return getString(index)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals(MediaStore.UNKNOWN_STRING, ignoreCase = true) }
    }

    private fun Cursor.longOrNull(columnName: String): Long? {
        val index = getColumnIndex(columnName)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private fun Cursor.positiveLongOrNull(columnName: String): Long? =
        longOrNull(columnName)?.takeIf { it > 0L }

    private fun String.withoutFileExtension(): String =
        substringBeforeLast('.', missingDelimiterValue = this).ifBlank { this }

    private fun audioQuality(mimeType: String?, bitrateKbps: Int?): AudioQuality? {
        if (mimeType == null && bitrateKbps == null) return null
        val tier = when {
            mimeType?.isLosslessAudio() == true -> QualityTier.LOSSLESS
            bitrateKbps != null && bitrateKbps >= 256 -> QualityTier.HIGH
            bitrateKbps != null && bitrateKbps < 128 -> QualityTier.LOW
            else -> QualityTier.STANDARD
        }
        return AudioQuality(tier = tier, codec = mimeType, bitrateKbps = bitrateKbps)
    }

    private fun String.isLosslessAudio(): Boolean {
        val normalized = lowercase()
        return normalized.contains("flac") ||
            normalized.contains("alac") ||
            normalized.contains("wav") ||
            normalized.contains("aiff")
    }

    private companion object {
        const val BITRATE_COLUMN = "bitrate"
        val ALBUM_ART_URI: Uri = Uri.parse("content://media/external/audio/albumart")
    }
}
