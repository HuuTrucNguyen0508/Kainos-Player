package com.universalmusic.player.data.local

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Recursive SAF tree scan for user-picked music folders.
 *
 * Uses [DocumentsContract] child cursors (one query per directory) instead of
 * [DocumentFile.listFiles] / per-property binder calls, which are prohibitively
 * slow on large trees. Throws [IllegalStateException] when a configured tree URI
 * is revoked.
 */
internal class SafLocalTrackSource(
    context: Context,
    private val treeUris: () -> List<String>,
) : LocalTrackSource {
    private val appContext = context.applicationContext
    private val contentResolver: ContentResolver = appContext.contentResolver

    override suspend fun scan(): List<LocalTrack> = withContext(Dispatchers.IO) {
        val uris = treeUris().map(String::trim).filter(String::isNotEmpty).distinct()
        if (uris.isEmpty()) return@withContext emptyList()

        val tracks = linkedMapOf<String, LocalTrack>()
        val revoked = mutableListOf<String>()
        for (raw in uris) {
            coroutineContext.ensureActive()
            val treeUri = runCatching { Uri.parse(raw) }.getOrNull()
            if (treeUri == null) {
                revoked += raw
                continue
            }
            if (!hasPersistedReadGrant(treeUri)) {
                revoked += displayName(treeUri)
                continue
            }
            val root = DocumentFile.fromTreeUri(appContext, treeUri)
            if (root == null || !root.canRead()) {
                revoked += displayName(treeUri)
                continue
            }
            val documentId = DocumentsContract.getTreeDocumentId(treeUri)
            walkDocuments(
                treeUri = treeUri,
                documentId = documentId,
                albumGroupKey = treeUri.toString(),
                displayName = root.name,
                into = tracks,
            )
        }
        lastRevokedFolders = revoked.toList()
        tracks.values.toList()
    }

    @Volatile
    var lastRevokedFolders: List<String> = emptyList()
        private set

    private fun hasPersistedReadGrant(treeUri: Uri): Boolean {
        return contentResolver.persistedUriPermissions.any { permission ->
            permission.isReadPermission && permission.uri == treeUri
        }
    }

    private fun displayName(uri: Uri): String =
        DocumentFile.fromTreeUri(appContext, uri)?.name?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment
            ?: uri.toString()

    private suspend fun walkDocuments(
        treeUri: Uri,
        documentId: String,
        albumGroupKey: String,
        displayName: String?,
        into: MutableMap<String, LocalTrack>,
    ) {
        coroutineContext.ensureActive()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val children = queryChildren(childrenUri) ?: return

        val directories = mutableListOf<ChildDoc>()
        val files = mutableListOf<ChildDoc>()
        for (child in children) {
            if (child.isDirectory) {
                directories += child
            } else {
                files += child
            }
        }

        val sidecarArt = files.findSidecarArtworkUri(treeUri)
        val directoryAlbum = displayName?.humanized()?.takeIf { it.isNotEmpty() && it != "Documents" }

        for (file in files) {
            coroutineContext.ensureActive()
            val extension = file.displayName.audioExtension() ?: continue
            file.toLocalTrack(
                treeUri = treeUri,
                albumGroupKey = albumGroupKey,
                directoryAlbum = directoryAlbum,
                sidecarArt = sidecarArt,
                extension = extension,
            )?.let { track ->
                into.putIfAbsent(track.location, track)
            }
        }

        for (dir in directories) {
            walkDocuments(
                treeUri = treeUri,
                documentId = dir.documentId,
                albumGroupKey = DocumentsContract.buildDocumentUriUsingTree(treeUri, dir.documentId).toString(),
                displayName = dir.displayName,
                into = into,
            )
        }
    }

    private fun queryChildren(childrenUri: Uri): List<ChildDoc>? {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        return runCatching {
            contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                buildList {
                    val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIdx) ?: continue
                        val name = cursor.getString(nameIdx) ?: continue
                        val mime = cursor.getString(mimeIdx).orEmpty()
                        val size = cursor.longOrNull(sizeIdx)
                        add(ChildDoc(documentId = id, displayName = name, mimeType = mime, size = size))
                    }
                }
            }
        }.getOrNull()
    }

    private fun List<ChildDoc>.findSidecarArtworkUri(treeUri: Uri): String? {
        val byLower = associateBy { it.displayName.lowercase() }
        for (name in LocalArtworkPolicy.SIDECAR_NAMES) {
            val file = byLower[name] ?: continue
            val size = file.size ?: continue
            if (size in 1..LocalArtworkPolicy.MAX_ARTWORK_BYTES) {
                return DocumentsContract.buildDocumentUriUsingTree(treeUri, file.documentId).toString()
            }
        }
        return null
    }

    private fun ChildDoc.toLocalTrack(
        treeUri: Uri,
        albumGroupKey: String,
        directoryAlbum: String?,
        sidecarArt: String?,
        extension: String,
    ): LocalTrack? {
        val stem = displayName.substringBeforeLast('.', displayName)
        val cleanedStem = stem.withoutTrackNumber().humanized()
        val artistTitleSeparator = cleanedStem.indexOf(" - ")
        val filenameArtist = cleanedStem
            .takeIf { artistTitleSeparator > 0 }
            ?.substring(0, artistTitleSeparator)
            ?.humanized()
            ?.takeIf(String::isNotEmpty)
        val title = cleanedStem
            .substring(if (artistTitleSeparator > 0) artistTitleSeparator + 3 else 0)
            .withoutTrackNumber()
            .humanized()
            .ifEmpty { stem.humanized() }
        if (title.isBlank()) return null
        val location = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString()
        return LocalTrack(
            id = UUID.nameUUIDFromBytes(location.toByteArray()).toString(),
            title = title,
            artists = listOfNotNull(filenameArtist),
            album = directoryAlbum,
            albumGroupKey = albumGroupKey,
            durationMs = null,
            artworkUri = sidecarArt,
            location = location,
            contentLength = size?.takeIf { it > 0 },
            quality = extension.toQuality(),
        )
    }

    private fun String?.audioExtension(): String? {
        val extension = this?.substringAfterLast('.', "")?.lowercase() ?: return null
        return extension.takeIf(SUPPORTED_AUDIO_EXTENSIONS::contains)
    }

    private fun String.toQuality(): AudioQuality = AudioQuality(
        tier = if (this in LOSSLESS_AUDIO_EXTENSIONS) QualityTier.LOSSLESS else QualityTier.STANDARD,
        codec = when (this) {
            "aif" -> "aiff"
            "wave" -> "wav"
            else -> this
        },
    )

    private fun String.withoutTrackNumber(): String = replace(TRACK_NUMBER_PREFIX, "")

    private fun String.humanized(): String = replace('_', ' ')
        .replace(REPEATED_WHITESPACE, " ")
        .trim()

    private fun Cursor.longOrNull(index: Int): Long? =
        if (isNull(index)) null else getLong(index)

    private data class ChildDoc(
        val documentId: String,
        val displayName: String,
        val mimeType: String,
        val size: Long?,
    ) {
        val isDirectory: Boolean
            get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
    }

    private companion object {
        val TRACK_NUMBER_PREFIX = Regex("""^\s*(?:(?:\d{1,2}(?:-\d{1,2})?)[\s._-]+)+""")
        val REPEATED_WHITESPACE = Regex("""\s+""")
        val LOSSLESS_AUDIO_EXTENSIONS = setOf("aif", "aiff", "alac", "flac", "wav", "wave")
        val SUPPORTED_AUDIO_EXTENSIONS = LOSSLESS_AUDIO_EXTENSIONS +
            setOf("aac", "m4a", "mp3", "oga", "ogg", "opus", "wma")
    }
}
