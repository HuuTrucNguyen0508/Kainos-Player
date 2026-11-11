package com.universalmusic.player.data.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.universalmusic.player.platform.androidContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual fun createLocalTagReader(): LocalTagReader? = AndroidLocalTagReader(androidContext)

/** Opens a SAF or MediaStore URI with [MediaMetadataRetriever], the reader embedded artwork uses too. */
internal class AndroidLocalTagReader(context: Context) : LocalTagReader {
    private val appContext = context.applicationContext

    override suspend fun read(location: String): LocalTags? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, Uri.parse(location))
            fun text(key: Int) = cleanTagText(retriever.extractMetadata(key))
            LocalTags(
                title = text(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artists = splitTagArtists(text(MediaMetadataRetriever.METADATA_KEY_ARTIST)),
                album = text(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist = text(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                trackNumber = parseTagPosition(text(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNumber = parseTagPosition(text(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                year = parseTagYear(text(MediaMetadataRetriever.METADATA_KEY_YEAR))
                    ?: parseTagYear(text(MediaMetadataRetriever.METADATA_KEY_DATE)),
                genre = text(MediaMetadataRetriever.METADATA_KEY_GENRE),
                durationMs = text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 },
            )
        } catch (failure: Throwable) {
            Log.w(TAG, "tag read failed for $location: ${failure.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private companion object {
        const val TAG = "KainosTags"
    }
}
