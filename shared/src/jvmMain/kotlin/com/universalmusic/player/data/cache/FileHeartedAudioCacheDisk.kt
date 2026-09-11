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
    private val root: Path,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : HeartedAudioCacheDisk {
    private val indexPath: Path = root.resolve("index.json")
    private val audioDir: Path = root.resolve("audio")

    override suspend fun loadIndex(): HeartedAudioCacheIndex {
        if (!indexPath.exists()) return HeartedAudioCacheIndex()
        return runCatching {
            json.decodeFromString<HeartedAudioCacheIndex>(indexPath.readText())
        }.getOrDefault(HeartedAudioCacheIndex())
    }

    override suspend fun saveIndex(index: HeartedAudioCacheIndex) {
        Files.createDirectories(root)
        Files.createDirectories(audioDir)
        val tmp = indexPath.resolveSibling("${indexPath.fileName}.tmp")
        tmp.writeText(json.encodeToString(index))
        try {
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, indexPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    override suspend fun deleteFile(uri: String) {
        pathFromUri(uri)?.let { runCatching { Files.deleteIfExists(it) } }
    }

    override suspend fun clearAll() {
        if (Files.isDirectory(audioDir)) {
            Files.list(audioDir).use { stream ->
                stream.asSequence().forEach { runCatching { Files.deleteIfExists(it) } }
            }
        }
        runCatching { Files.deleteIfExists(indexPath) }
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

    private fun pathFromUri(uri: String): Path? = runCatching {
        when {
            uri.startsWith("file:") -> Path.of(java.net.URI(uri))
            else -> Path.of(uri)
        }
    }.getOrNull()
}
