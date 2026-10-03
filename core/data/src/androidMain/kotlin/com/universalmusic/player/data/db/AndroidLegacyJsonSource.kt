package com.universalmusic.player.data.db

import android.content.Context
import com.universalmusic.player.data.library.AndroidUserLibraryStore
import com.universalmusic.player.data.local.AndroidLocalLibraryScanCache
import com.universalmusic.player.data.playlist.AndroidKainosPlaylistStore
import com.universalmusic.player.data.session.AndroidSessionSnapshotStore
import java.io.File

internal class AndroidLegacyJsonSource(
    context: Context,
) : LegacyJsonSource {
    private val dir = context.applicationContext.filesDir
    private val appContext = context.applicationContext

    override suspend fun load(): LegacyJsonData {
        val userLibrary = readIfExists(USER_LIBRARY_FILE) {
            AndroidUserLibraryStore(appContext).read()
        }
        val playlists = readIfExists(PLAYLISTS_FILE) {
            AndroidKainosPlaylistStore(appContext).read()
        }
        val session = readIfExists(SESSION_FILE) {
            AndroidSessionSnapshotStore(appContext).read()
        }
        val local = if (File(dir, LOCAL_CACHE_FILE).exists()) {
            AndroidLocalLibraryScanCache(appContext).readAnyConfig()
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
            val file = File(dir, name)
            if (!file.exists()) continue
            val migrated = File(dir, "$name.migrated")
            if (migrated.exists()) migrated.delete()
            if (!file.renameTo(migrated)) {
                file.copyTo(migrated, overwrite = true)
                file.delete()
            }
        }
    }

    private suspend fun <T> readIfExists(name: String, read: suspend () -> T): T? {
        if (!File(dir, name).exists()) return null
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
