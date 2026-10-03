package com.universalmusic.player.data.cache

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence

class FileMetadataCacheDisk(
    private val root: Path,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : MetadataCacheDisk {
    private val indexPath: Path = root.resolve("index.json")
    private val artDir: Path = root.resolve("art")

    override suspend fun loadIndex(): MetadataCacheIndex {
        if (!indexPath.exists()) return MetadataCacheIndex()
        return runCatching {
            json.decodeFromString<MetadataCacheIndex>(indexPath.readText())
        }.getOrDefault(MetadataCacheIndex())
    }

    override suspend fun saveIndex(index: MetadataCacheIndex) {
        Files.createDirectories(root)
        val tmp = indexPath.resolveSibling("${indexPath.fileName}.tmp")
        tmp.writeText(json.encodeToString(index))
        try {
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override suspend fun saveArtwork(canonicalId: String, bytes: ByteArray): String? {
        Files.createDirectories(artDir)
        val name = fileNameFor(canonicalId)
        val file = artDir.resolve(name)
        val tmp = artDir.resolve("$name.tmp")
        Files.write(tmp, bytes)
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
        return file.toUri().toASCIIString()
    }

    override suspend fun deleteArtwork(canonicalId: String) {
        val file = artDir.resolve(fileNameFor(canonicalId))
        runCatching { Files.deleteIfExists(file) }
    }

    override suspend fun clearAll() {
        if (Files.isDirectory(artDir)) {
            Files.list(artDir).use { stream ->
                stream.asSequence().forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        runCatching { Files.deleteIfExists(indexPath) }
        Files.createDirectories(root)
    }

    override fun artworkExists(uri: String): Boolean {
        if (uri.startsWith("file:")) {
            return runCatching { Files.isRegularFile(Path.of(java.net.URI(uri))) }.getOrDefault(false)
        }
        return runCatching { Files.isRegularFile(Path.of(uri)) }.getOrDefault(false)
    }

    override suspend fun artworkBytesUsed(): Long {
        if (!Files.isDirectory(artDir)) return 0L
        return Files.list(artDir).use { stream ->
            stream.asSequence().sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        }
    }

    override suspend fun artworkFileCount(): Int {
        if (!Files.isDirectory(artDir)) return 0
        return Files.list(artDir).use { stream -> stream.count().toInt() }
    }

    private fun fileNameFor(canonicalId: String): String {
        val id = UUID.nameUUIDFromBytes(canonicalId.toByteArray(StandardCharsets.UTF_8)).toString()
        return "$id.jpg"
    }
}
