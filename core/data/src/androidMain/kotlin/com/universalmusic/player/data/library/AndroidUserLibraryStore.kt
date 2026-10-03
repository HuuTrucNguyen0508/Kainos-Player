package com.universalmusic.player.data.library

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class AndroidUserLibraryStore(
    context: Context,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : UserLibraryStore {
    private val file = File(context.applicationContext.filesDir, "user-library.json")

    override suspend fun read(): UserLibrarySnapshot {
        if (!file.exists()) return UserLibrarySnapshot()
        return runCatching {
            json.decodeFromString<UserLibrarySnapshot>(file.readText()).migrated()
        }.getOrDefault(UserLibrarySnapshot())
    }

    override suspend fun write(snapshot: UserLibrarySnapshot) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(json.encodeToString(snapshot))
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
