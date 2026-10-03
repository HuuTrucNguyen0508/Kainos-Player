package com.universalmusic.player.data.local

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists the last successful Android local library scan so launch can show tracks
 * immediately while a background rescan runs.
 */
internal class AndroidLocalLibraryScanCache(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : LocalLibraryScanCache {
    private val file = File(context.applicationContext.filesDir, "local-library-cache.json")

    override suspend fun read(configKey: String): List<LocalTrack>? {
        val stored = readAnyConfig() ?: return null
        if (stored.first != configKey) return null
        return stored.second
    }

    internal suspend fun readAnyConfig(): Pair<String, List<LocalTrack>>? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        runCatching {
            val snapshot = json.decodeFromString<LocalLibraryCacheSnapshot>(file.readText())
            snapshot.configKey to snapshot.tracks.mapNotNull { it.toLocalTrackOrNull() }
        }.getOrNull()
    }

    override suspend fun write(configKey: String, tracks: List<LocalTrack>) = withContext(Dispatchers.IO) {
        val snapshot = LocalLibraryCacheSnapshot(
            configKey = configKey,
            tracks = tracks.map { it.toStored() },
        )
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(snapshot))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        runCatching { file.delete() }
    }
}

@Serializable
private data class LocalLibraryCacheSnapshot(
    val configKey: String,
    val tracks: List<StoredLocalTrack> = emptyList(),
)
