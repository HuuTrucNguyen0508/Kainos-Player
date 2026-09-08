package com.universalmusic.player.data.library

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileUserLibraryStore(
    private val path: Path,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : UserLibraryStore {
    override suspend fun read(): UserLibrarySnapshot {
        if (!path.exists()) return UserLibrarySnapshot()
        return runCatching {
            json.decodeFromString<UserLibrarySnapshot>(path.readText()).migrated()
        }.getOrDefault(UserLibrarySnapshot())
    }

    override suspend fun write(snapshot: UserLibrarySnapshot) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling("${path.fileName}.tmp")
        tmp.writeText(json.encodeToString(snapshot))
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
