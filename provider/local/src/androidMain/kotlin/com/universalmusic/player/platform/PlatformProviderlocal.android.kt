package com.universalmusic.player.platform

import android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
import android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
import android.net.Uri
import com.universalmusic.player.data.local.LocalLibraryRootMode
import com.universalmusic.player.data.local.LocalLibraryScanConfig
import com.universalmusic.player.data.local.LocalTrack
import com.universalmusic.player.data.local.LocalTrackSource
import com.universalmusic.player.data.local.MediaStoreLocalTrackSource
import com.universalmusic.player.data.local.SafLocalTrackSource
import com.universalmusic.player.data.local.dedupeKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

var launchMusicFolderPicker: (() -> Unit)? = null

object MusicFolderPickerRelay {
    @Volatile
    var pending: CompletableDeferred<String?>? = null

    fun complete(uri: String?) {
        pending?.complete(uri)
        pending = null
    }
}

actual fun createLocalTrackSource(config: () -> LocalLibraryScanConfig): LocalTrackSource =
    LocalTrackSource {
        val scan = config()
        val merged = linkedMapOf<String, LocalTrack>()
        fun ingest(tracks: List<LocalTrack>) {
            for (track in tracks) {
                merged.putIfAbsent(track.dedupeKey(), track)
            }
        }
        val revoked = mutableListOf<String>()
        when (scan.mode) {
            LocalLibraryRootMode.USE_DEFAULTS -> {
                if (scan.includeMediaStore) {
                    ingest(MediaStoreLocalTrackSource(androidContext).scan())
                }
            }
            LocalLibraryRootMode.EXPLICIT -> {
                if (scan.folders.isNotEmpty()) {
                    val saf = SafLocalTrackSource(androidContext) { scan.folders }
                    ingest(saf.scan())
                    revoked += saf.lastRevokedFolders
                }
                if (scan.includeMediaStore) {
                    ingest(MediaStoreLocalTrackSource(androidContext).scan())
                }
            }
        }
        if (merged.isEmpty() && revoked.isNotEmpty()) {
            throw IllegalStateException(
                "Lost access to music folder(s): ${revoked.joinToString()}. " +
                    "Remove them in Settings or re-add the folder to restore access.",
            )
        }
        merged.values.toList()
    }

actual fun defaultLocalMusicFolder(): String = ""

actual fun supportsMusicFolderPicker(): Boolean = true

actual suspend fun pickMusicFolder(): String? {
    val deferred = CompletableDeferred<String?>()
    MusicFolderPickerRelay.pending?.cancel()
    MusicFolderPickerRelay.pending = deferred
    val launcher = launchMusicFolderPicker
    if (launcher == null) {
        MusicFolderPickerRelay.pending = null
        return null
    }
    withContext(Dispatchers.Main) { launcher.invoke() }
    return withTimeoutOrNull(180_000) { deferred.await() }.also {
        if (MusicFolderPickerRelay.pending === deferred) {
            MusicFolderPickerRelay.pending = null
        }
    }
}

actual fun releaseMusicFolderAccess(folder: String) {
    val uri = runCatching { Uri.parse(folder) }.getOrNull() ?: return
    runCatching {
        androidContext.contentResolver.releasePersistableUriPermission(
            uri,
            FLAG_GRANT_READ_URI_PERMISSION or FLAG_GRANT_WRITE_URI_PERMISSION,
        )
    }
}
