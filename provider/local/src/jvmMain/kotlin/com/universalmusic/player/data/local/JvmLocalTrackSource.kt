package com.universalmusic.player.data.local

import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityConfidence
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
import kotlinx.coroutines.runInterruptible

class JvmLocalTrackSource(
    private val optionalRootsProvider: () -> Set<Path> = { setOf(Paths.get(System.getProperty("user.home", "."), "Music")) },
    private val rootsProvider: () -> List<Path> = { defaultMusicRoots() },
) : IncrementalLocalTrackSource {
    constructor(roots: List<Path>) : this(optionalRootsProvider = { emptySet() }, rootsProvider = { roots })

    override suspend fun scan(): List<LocalTrack> = scanReusing(emptyList()).tracks

    override suspend fun scanReusing(previous: List<LocalTrack>): LocalScanResult = withContext(Dispatchers.IO) {
        val discovered = linkedMapOf<Path, Path>()
        val roots = rootsProvider().map { it.toAbsolutePath().normalize() }.distinct()
        val optional = optionalRootsProvider().map { it.toAbsolutePath().normalize() }.toSet()
        val unavailable = mutableListOf<Path>()
        // Folders and files inside a readable root that the walk could not enter or read.
        val skipped = mutableListOf<Path>()
        var readable = 0
        roots.forEach { root ->
            val found = linkedMapOf<Path, Path>()
            try {
                scanRoot(root, found, skipped)
                check(isReadableDirectory(root))
                discovered.putAll(found)
                readable++
            } catch (_: IllegalStateException) {
                if (root !in optional) unavailable += listOf(root)
            }
        }
        val previousByLocation = previous.associateBy(LocalTrack::location)
        var reused = 0
        var probed = 0
        val tracks = discovered.entries
            .sortedBy { it.key.toString() }
            .map { (file, root) ->
                val fingerprint = file.fingerprintOrNull()
                val cached = fingerprint?.let { previousByLocation[it.location] }
                if (
                    cached != null &&
                    fingerprint.sizeBytes != null &&
                    fingerprint.modifiedEpochMs != null &&
                    cached.contentLength == fingerprint.sizeBytes &&
                    cached.fileModifiedEpochMs == fingerprint.modifiedEpochMs &&
                    cached.tagsRead
                ) {
                    reused++
                    if (cached.contentKey == null) cached.copy(contentKey = file.readLocalContentKey()) else cached
                } else {
                    probed++
                    runInterruptible { file.toLocalTrack(root, fingerprint) }
                }
            }
        val retained = previous.filter { track ->
            val path = runCatching { Paths.get(java.net.URI(track.location)).toAbsolutePath().normalize() }.getOrNull()
            path != null && (unavailable.any { path.startsWith(it) } || skipped.any { path.startsWith(it) })
        }
        LocalScanResult(
            tracks = (tracks + retained).distinctBy(LocalTrack::location), reused = reused, probed = probed,
            unavailableRoots = unavailable.map(Path::toString), readableRootCount = readable,
        )
    }

    private fun scanRoot(root: Path, discovered: MutableMap<Path, Path>, skipped: MutableList<Path>) {
        check(isReadableDirectory(root)) { "Cannot read music folder: $root. Reconnect the drive and retry." }
        try {
            Files.walkFileTree(
                root,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
                        if (isReadableDirectory(directory)) {
                            FileVisitResult.CONTINUE
                        } else {
                            normalizedPathOrNull(directory)?.let(skipped::add)
                            FileVisitResult.SKIP_SUBTREE
                        }

                    override fun visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult {
                        if (attributes.isRegularFile && Files.isReadable(file) && file.audioExtension() != null) {
                            normalizedPathOrNull(file)?.let { discovered.putIfAbsent(it, root) }
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(file: Path, error: IOException): FileVisitResult {
                        if (file == root) throw error
                        normalizedPathOrNull(file)?.let(skipped::add)
                        return FileVisitResult.CONTINUE
                    }
                },
            )
        } catch (_: IOException) {
            throw IllegalStateException("Cannot read music folder: $root. Reconnect the drive and retry.")
        } catch (_: SecurityException) {
            throw IllegalStateException("Cannot read music folder: $root. Check folder access and retry.")
        }
    }
}

/**
 * @param foldersConfigured when false and [configuredFolders] is empty, use ~/Music.
 *   when true and empty, do not add the default Music folder.
 */
internal fun resolveMusicRoots(
    homeDirectory: Path,
    configuredFolders: List<String> = emptyList(),
    foldersConfigured: Boolean = configuredFolders.isNotEmpty(),
    additionalRoots: String? = null,
): List<Path> = buildList {
    val explicit = configuredFolders
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { runCatching { Paths.get(it) }.getOrNull() }
        .toList()
    when {
        !foldersConfigured && explicit.isEmpty() -> add(homeDirectory.resolve("Music"))
        else -> addAll(explicit)
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
    foldersConfigured = false,
    additionalRoots = System.getenv("KAINOS_MUSIC_DIRS"),
)

private data class FileFingerprint(
    val location: String,
    val sizeBytes: Long?,
    val modifiedEpochMs: Long?,
)

private fun Path.fingerprintOrNull(): FileFingerprint? {
    val location = runCatching { toUri().toASCIIString() }.getOrNull() ?: return null
    val size = runCatching { Files.size(this) }.getOrNull()?.takeIf { it > 0 }
    val modified = runCatching { Files.getLastModifiedTime(this).toMillis() }.getOrNull()
    return FileFingerprint(location, size, modified)
}

private fun Path.toLocalTrack(root: Path, fingerprint: FileFingerprint? = fingerprintOrNull()): LocalTrack {
    val relative = runCatching { root.relativize(this) }.getOrElse { fileName }
    val guessed = filenameTags(fileName.toString())
    val directoryArtist = relative.directoryPartFromEnd(2)
    val directoryAlbum = relative.directoryPartFromEnd(1)
    val extension = audioExtension() ?: error("Unsupported local audio file: $this")
    val probed = probeLocalAudioMetadata(this, extension.toQuality())

    return LocalTrack(
        id = stableId(this),
        title = guessed.title,
        artists = listOfNotNull(guessed.artist ?: directoryArtist),
        album = directoryAlbum,
        albumGroupKey = (parent ?: this).toAbsolutePath().normalize().toString(),
        location = fingerprint?.location ?: toUri().toASCIIString(),
        contentLength = fingerprint?.sizeBytes,
        fileModifiedEpochMs = fingerprint?.modifiedEpochMs,
        artworkUri = resolveLocalArtworkUri(this, probed.embeddedArtworkPath),
        quality = probed.quality,
        contentKey = readLocalContentKey(),
    ).withTags(probed.tags)
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
    confidence = QualityConfidence.ASSUMED,
    codec = when (this) {
        "aif" -> "aiff"
        "wave" -> "wav"
        else -> this
    },
)

private fun String.humanized(): String = replace('_', ' ')
    .replace(REPEATED_WHITESPACE, " ")
    .trim()

private val REPEATED_WHITESPACE = Regex("""\s+""")

private val LOSSLESS_AUDIO_EXTENSIONS = setOf("aif", "aiff", "alac", "flac", "wav", "wave")
private val SUPPORTED_AUDIO_EXTENSIONS = LOSSLESS_AUDIO_EXTENSIONS +
    setOf("aac", "m4a", "mp3", "oga", "ogg", "opus", "wma")
