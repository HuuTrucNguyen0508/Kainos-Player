package com.universalmusic.player.data.playlist

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AndroidKainosPlaylistStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : KainosPlaylistStore {
    private val file = File(context.applicationContext.filesDir, "kainos-playlists.json")

    override suspend fun read(): KainosPlaylistsSnapshot {
        if (!file.exists()) return KainosPlaylistsSnapshot()
        return runCatching {
            json.decodeFromString<KainosPlaylistsSnapshot>(file.readText()).migrated()
        }.getOrDefault(KainosPlaylistsSnapshot())
    }

    override suspend fun write(snapshot: KainosPlaylistsSnapshot) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(snapshot))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
