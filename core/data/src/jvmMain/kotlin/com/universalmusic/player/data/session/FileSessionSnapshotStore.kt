package com.universalmusic.player.data.session

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class FileSessionSnapshotStore(
    private val path: Path,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : SessionSnapshotStore {
    override suspend fun read(): SessionSnapshot {
        if (!path.exists()) return SessionSnapshot()
        return runCatching {
            json.decodeFromString<SessionSnapshot>(path.readText()).migrated()
        }.getOrDefault(SessionSnapshot())
    }

    override suspend fun write(snapshot: SessionSnapshot) {
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
