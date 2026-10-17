package com.universalmusic.player.data.cache

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.streams.asSequence

class FileHeartedAudioCacheDisk(
    root: Path,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : HeartedAudioCacheDisk {
    private val root = root.toAbsolutePath().normalize()
    private val indexPath: Path = this.root.resolve("index.json")
    private val audioDir: Path = this.root.resolve("audio")

    private val startupFiles = if (Files.isDirectory(audioDir)) {
        Files.list(audioDir).use { it.filter(Files::isRegularFile).toList() }
    } else emptyList()
    private var reconciled = false
    private var indexReadable = true

    override suspend fun loadIndex(): HeartedAudioCacheIndex {
        if (!indexPath.exists()) return HeartedAudioCacheIndex()
        return runCatching {
            val index = json.decodeFromString<HeartedAudioCacheIndex>(indexPath.readText())
            index.copy(entries = index.entries.mapNotNull { (id, entry) ->
                val path = pathFromUri(entry.localUri) ?: return@mapNotNull null
                if (isCachePath(path)) return@mapNotNull id to entry
                val copy = audioDir.resolve(path.fileName ?: return@mapNotNull null)
                if (isCachePath(copy) && Files.isRegularFile(copy)) {
                    id to entry.copy(localUri = copy.toUri().toASCIIString())
                } else null
            }.toMap())
        }.getOrElse { indexReadable = false; HeartedAudioCacheIndex() }
    }

    override suspend fun saveIndex(index: HeartedAudioCacheIndex) {
        Files.createDirectories(root)
        Files.createDirectories(audioDir)
        val tmp = Files.createTempFile(root, "index-", ".tmp")
        try {
            tmp.writeText(json.encodeToString(index))
            try {
                Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            deleteCacheFile(tmp)
        }
    }

    override suspend fun reconcile(referencedUris: Set<String>) {
        if (reconciled || !indexReadable) return
        val referenced = referencedUris.map(::normalizedAudioUri).toSet()
        startupFiles.filter { normalizedAudioUri(it.toString()) !in referenced }.forEach(::deleteCacheFile)
        reconciled = true
    }

    override suspend fun deleteFile(uri: String) {
        pathFromUri(uri)?.let(::deleteCacheFile)
    }

    override suspend fun clearAll() {
        if (Files.isDirectory(audioDir)) {
            Files.list(audioDir).use { stream ->
                stream.asSequence().forEach(::deleteCacheFile)
            }
        }
        deleteCacheFile(indexPath)
        Files.createDirectories(root)
        Files.createDirectories(audioDir)
    }

    override fun fileExists(uri: String): Boolean {
        val path = pathFromUri(uri) ?: return false
        return runCatching { Files.isRegularFile(path) && Files.size(path) > 0L }.getOrDefault(false)
    }

    override fun audioDirectoryPath(): String {
        Files.createDirectories(audioDir)
        return audioDir.toAbsolutePath().normalize().toString()
    }

    override suspend fun bytesUsed(): Long {
        if (!Files.isDirectory(audioDir)) return 0L
        return Files.list(audioDir).use { stream ->
            stream.asSequence().sumOf { runCatching { Files.size(it) }.getOrDefault(0L) }
        }
    }

    private fun isCachePath(path: Path): Boolean {
        val normalized = path.toAbsolutePath().normalize()
        return normalized != root && normalized.startsWith(root)
    }

    private fun deleteCacheFile(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        if (!isCachePath(normalized)) return
        val insideRoot = runCatching {
            normalized.toFile().canonicalFile.toPath().startsWith(root.toFile().canonicalFile.toPath())
        }.getOrDefault(false)
        if (insideRoot) runCatching { Files.deleteIfExists(normalized) }
    }

    private fun pathFromUri(uri: String): Path? = runCatching {
        when {
            uri.startsWith("file:") -> Path.of(java.net.URI(uri))
            else -> Path.of(uri)
        }
    }.getOrNull()
}
