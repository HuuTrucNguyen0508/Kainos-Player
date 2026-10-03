package com.universalmusic.player.data.db

import com.universalmusic.player.data.library.FileUserLibraryStore
import com.universalmusic.player.data.local.JvmLocalLibraryScanCache
import com.universalmusic.player.data.playlist.FileKainosPlaylistStore
import com.universalmusic.player.data.session.FileSessionSnapshotStore
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class JvmLegacyJsonSource(
    private val dir: Path,
) : LegacyJsonSource {
    override suspend fun load(): LegacyJsonData {
        val userLibrary = readIfExists(USER_LIBRARY_FILE) {
            FileUserLibraryStore(dir.resolve(USER_LIBRARY_FILE)).read()
        }
        val playlists = readIfExists(PLAYLISTS_FILE) {
            FileKainosPlaylistStore(dir.resolve(PLAYLISTS_FILE)).read()
        }
        val session = readIfExists(SESSION_FILE) {
            FileSessionSnapshotStore(dir.resolve(SESSION_FILE)).read()
        }
        val local = if (Files.exists(dir.resolve(LOCAL_CACHE_FILE))) {
            JvmLocalLibraryScanCache(dir.resolve(LOCAL_CACHE_FILE)).readAnyConfig()
        } else {
            null
        }
        return LegacyJsonData(
            userLibrary = userLibrary,
            playlists = playlists,
            session = session,
            localScanConfigKey = local?.first,
            localScanTracks = local?.second.orEmpty(),
        )
    }

    override fun retire() {
        for (name in LEGACY_FILES) {
            val path = dir.resolve(name)
            if (!Files.exists(path)) continue
            val migrated = dir.resolve("$name.migrated")
            try {
                Files.move(
                    path,
                    migrated,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(path, migrated, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private suspend fun <T> readIfExists(name: String, read: suspend () -> T): T? {
        if (!Files.exists(dir.resolve(name))) return null
        return read()
    }

    private companion object {
        const val USER_LIBRARY_FILE = "user-library.json"
        const val PLAYLISTS_FILE = "kainos-playlists.json"
        const val SESSION_FILE = "playback-session.json"
        const val LOCAL_CACHE_FILE = "local-library-cache.json"
        val LEGACY_FILES = listOf(USER_LIBRARY_FILE, PLAYLISTS_FILE, SESSION_FILE, LOCAL_CACHE_FILE)
    }
}
