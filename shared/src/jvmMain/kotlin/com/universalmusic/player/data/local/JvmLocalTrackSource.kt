package com.universalmusic.player.data.local

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityTier
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class JvmLocalTrackSource(
    private val rootsProvider: () -> List<Path> = { defaultMusicRoots() },
) : LocalTrackSource {
    constructor(roots: List<Path>) : this({ roots })

    override suspend fun scan(): List<LocalTrack> = withContext(Dispatchers.IO) {
        val discovered = linkedMapOf<Path, Path>()
        rootsProvider()
            .mapNotNull(::normalizedPathOrNull)
            .distinct()
            .forEach { root -> scanRoot(root, discovered) }

        discovered.entries
            .sortedBy { it.key.toString() }
            .map { (file, root) -> file.toLocalTrack(root) }
    }

    private fun scanRoot(root: Path, discovered: MutableMap<Path, Path>) {
        if (!isReadableDirectory(root)) return
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
                        if (isReadableDirectory(directory)) FileVisitResult.CONTINUE else FileVisitResult.SKIP_SUBTREE

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        if (attributes.isRegularFile && Files.isReadable(file) && file.audioExtension() != null) {
                            normalizedPathOrNull(file)?.let { discovered.putIfAbsent(it, root) }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, error: IOException): FileVisitResult =
                        FileVisitResult.CONTINUE
                },
            )
        } catch (_: IOException) {
            // A disappearing or unreadable root contributes no more files to this snapshot.
        } catch (_: SecurityException) {
            // Security policy failures are treated like unreadable roots.
        }
    }
}

internal fun resolveMusicRoots(
    homeDirectory: Path,
    configuredFolders: List<String> = emptyList(),
    additionalRoots: String? = null,
): List<Path> = buildList {
    if (configuredFolders.isEmpty()) {
        add(homeDirectory.resolve("Music"))
    } else {
        configuredFolders
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .mapNotNull { runCatching { Paths.get(it) }.getOrNull() }
            .let(::addAll)
    }
    additionalRoots
        ?.split(File.pathSeparatorChar)
        ?.asSequence()
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.mapNotNull { runCatching { Paths.get(it) }.getOrNull() }
        ?.let(::addAll)
}.mapNotNull(::normalizedPathOrNull).distinct()

private fun defaultMusicRoots(): List<Path> = resolveMusicRoots(
    homeDirectory = Paths.get(System.getProperty("user.home", ".")),
    additionalRoots = System.getenv("KAINOS_MUSIC_DIRS"),
)

private fun Path.toLocalTrack(root: Path): LocalTrack {
    val relative = runCatching { root.relativize(this) }.getOrElse { fileName }
    val stem = fileName.toString().substringBeforeLast('.', fileName.toString())
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
    val directoryArtist = relative.directoryPartFromEnd(2)
    val directoryAlbum = relative.directoryPartFromEnd(1)
    val extension = audioExtension() ?: error("Unsupported local audio file: $this")

    return LocalTrack(
        id = stableId(this),
        title = title,
        artists = listOfNotNull(filenameArtist ?: directoryArtist),
        album = directoryAlbum,
        location = toUri().toASCIIString(),
        quality = probeLocalAudioQuality(this, extension.toQuality()),
    )
}

private fun Path.directoryPartFromEnd(offset: Int): String? {
    if (nameCount < 3) return null
    return getName(nameCount - 1 - offset)
        .toString()
        .humanized()
        .takeIf(String::isNotEmpty)
}

private fun stableId(path: Path): String = UUID.nameUUIDFromBytes(
    path.toString().toByteArray(StandardCharsets.UTF_8),
).toString()

private fun normalizedPathOrNull(path: Path): Path? = try {
    path.toAbsolutePath().normalize()
} catch (_: SecurityException) {
    null
}

private fun isReadableDirectory(path: Path): Boolean = try {
    Files.isDirectory(path) && Files.isReadable(path)
} catch (_: SecurityException) {
    false
}

private fun Path.audioExtension(): String? {
    val extension = fileName.toString().substringAfterLast('.', "").lowercase()
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

private val TRACK_NUMBER_PREFIX = Regex("""^\s*(?:(?:\d{1,2}(?:-\d{1,2})?)[\s._-]+)+""")
private val REPEATED_WHITESPACE = Regex("""\s+""")

private val LOSSLESS_AUDIO_EXTENSIONS = setOf("aif", "aiff", "alac", "flac", "wav", "wave")
private val SUPPORTED_AUDIO_EXTENSIONS = LOSSLESS_AUDIO_EXTENSIONS +
    setOf("aac", "m4a", "mp3", "oga", "ogg", "opus", "wma")
