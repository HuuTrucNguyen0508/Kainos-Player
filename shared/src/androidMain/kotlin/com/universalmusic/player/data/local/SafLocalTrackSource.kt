package com.universalmusic.player.data.local

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Recursive SAF tree scan for user-picked music folders.
 * Throws [IllegalStateException] with a clear message when a configured tree URI is revoked.
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
            walkDocuments(root, albumGroupKey = treeUri.toString(), into = tracks)
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

    private fun walkDocuments(
        directory: DocumentFile,
        albumGroupKey: String,
        into: MutableMap<String, LocalTrack>,
    ) {
        val children = directory.listFiles()
        for (child in children) {
            when {
                child.isDirectory -> {
                    val childKey = child.uri.toString()
                    walkDocuments(child, albumGroupKey = childKey, into = into)
                }
                child.isFile && child.canRead() && child.name.audioExtension() != null -> {
                    child.toLocalTrack(albumGroupKey)?.let { track ->
                        into.putIfAbsent(track.location, track)
                    }
                }
            }
        }
    }

    private fun DocumentFile.toLocalTrack(albumGroupKey: String): LocalTrack? {
        val name = name ?: return null
        val extension = name.audioExtension() ?: return null
        val stem = name.substringBeforeLast('.', name)
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
        val parent = parentFile
        val directoryAlbum = parent?.name?.humanized()?.takeIf { it.isNotEmpty() && it != "Documents" }
        val directoryArtist = parent?.parentFile?.name?.humanized()?.takeIf { it.isNotEmpty() }
        val sidecarArt = parent?.findSidecarArtwork()?.uri?.toString()
        val length = length().takeIf { it > 0 }

        return LocalTrack(
            id = UUID.nameUUIDFromBytes(uri.toString().toByteArray()).toString(),
            title = title,
            artists = listOfNotNull(filenameArtist ?: directoryArtist),
            album = directoryAlbum,
            albumGroupKey = albumGroupKey,
            durationMs = null,
            artworkUri = sidecarArt,
            location = uri.toString(),
            contentLength = length,
            quality = extension.toQuality(),
        )
    }

    private fun DocumentFile.findSidecarArtwork(): DocumentFile? {
        val children = listFiles()
        val byLower = children.filter { it.isFile }.associateBy { it.name?.lowercase().orEmpty() }
        for (name in LocalArtworkPolicy.SIDECAR_NAMES) {
            val file = byLower[name] ?: continue
            val size = file.length()
            if (size in 1..LocalArtworkPolicy.MAX_ARTWORK_BYTES) return file
        }
        return null
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

    private companion object {
        val TRACK_NUMBER_PREFIX = Regex("""^\s*(?:(?:\d{1,2}(?:-\d{1,2})?)[\s._-]+)+""")
        val REPEATED_WHITESPACE = Regex("""\s+""")
        val LOSSLESS_AUDIO_EXTENSIONS = setOf("aif", "aiff", "alac", "flac", "wav", "wave")
        val SUPPORTED_AUDIO_EXTENSIONS = LOSSLESS_AUDIO_EXTENSIONS +
            setOf("aac", "m4a", "mp3", "oga", "ogg", "opus", "wma")
    }
}
