package com.universalmusic.player.data.session

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AndroidSessionSnapshotStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SessionSnapshotStore {
    private val file = File(context.applicationContext.filesDir, "playback-session.json")

    override suspend fun read(): SessionSnapshot {
        if (!file.exists()) return SessionSnapshot()
        return runCatching {
            json.decodeFromString<SessionSnapshot>(file.readText()).migrated()
        }.getOrDefault(SessionSnapshot())
    }

    override suspend fun write(snapshot: SessionSnapshot) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(snapshot))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
