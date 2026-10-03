package com.universalmusic.player.data.local

import com.universalmusic.player.platform.homeLanConfigDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Disk snapshot of the last desktop library scan so the next launch can show tracks
 * immediately and skip ffprobe for files whose path, size, and mtime are unchanged.
 */
internal class JvmLocalLibraryScanCache(
    private val file: Path = homeLanConfigDir().resolve("local-library-cache.json"),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : LocalLibraryScanCache {
    override suspend fun read(configKey: String): List<LocalTrack>? {
        val stored = readAnyConfig() ?: return null
        if (stored.first != configKey) return null
        return stored.second
    }

    internal suspend fun readAnyConfig(): Pair<String, List<LocalTrack>>? = withContext(Dispatchers.IO) {
        if (!Files.exists(file)) return@withContext null
        runCatching {
            val snapshot = json.decodeFromString<JvmLibraryCacheSnapshot>(Files.readString(file))
            snapshot.configKey to snapshot.tracks.mapNotNull { it.toLocalTrackOrNull() }
        }.getOrNull()
    }

    override suspend fun write(configKey: String, tracks: List<LocalTrack>) {
        withContext(Dispatchers.IO) {
            val snapshot = JvmLibraryCacheSnapshot(
                configKey = configKey,
                tracks = tracks.map { it.toStored() },
            )
            Files.createDirectories(file.parent)
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, json.encodeToString(snapshot))
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}

@Serializable
private data class JvmLibraryCacheSnapshot(
    val configKey: String,
    val tracks: List<StoredLocalTrack> = emptyList(),
)
