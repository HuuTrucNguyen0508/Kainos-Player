package com.universalmusic.player.data.backup

import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.library.portableForHomeSync
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.playlist.isKainosPlaylistId
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A file that cannot be restored; [message] is shown to the user as is. */
class BackupFileException(message: String) : Exception(message)

private val backupJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

fun encodeKainosBackup(file: KainosBackupFile): String = backupJson.encodeToString(KainosBackupFile.serializer(), file)

fun decodeKainosBackup(text: String): KainosBackupFile {
    val file = try {
        backupJson.decodeFromString(KainosBackupFile.serializer(), text)
    } catch (_: SerializationException) {
        throw BackupFileException("This file is not a Kainos backup.")
    } catch (_: IllegalArgumentException) {
        throw BackupFileException("This file is not a Kainos backup.")
    }
    if (file.format != KAINOS_BACKUP_FORMAT || file.version < 1) throw BackupFileException("This file is not a Kainos backup.")
    if (file.version > KAINOS_BACKUP_VERSION) {
        throw BackupFileException("This backup comes from a newer Kainos Player. Update the app, then restore it.")
    }
    return file
}

/** Export and restore of one [KainosBackupFile]. See its documentation for the merge rules. */
class KainosBackupService(
    private val library: LibraryRepository,
    private val playlists: KainosPlaylistRepository,
    private val settings: () -> AppSettings,
    private val updateSettings: suspend ((AppSettings) -> AppSettings) -> Unit,
    private val clock: () -> Long = ::currentTimeMillis,
) {
    suspend fun export(): KainosBackupFile {
        library.awaitLoaded()
        playlists.awaitLoaded()
        val live = playlists.backupPlaylists()
        val liveIds = live.mapTo(HashSet()) { it.id }
        val snapshot = library.toSnapshot()
        return KainosBackupFile(
            createdAtMs = clock(),
            spotifyAccountId = snapshot.spotifyAccountId,
            hearts = library.backupHearts(),
            playlists = live,
            homePins = snapshot.homePins.portableForHomeSync()
                .filter { it.kind != HomePinKind.KAINOS_PLAYLIST || it.targetId in liveIds },
            preferences = settings().backupPreferences(),
        )
    }

    /** What [restore] would do now; changes nothing. */
    suspend fun preview(file: KainosBackupFile): BackupRestoreCounts = run(file, apply = false)

    /** Playlists first so their pins find them; every step is idempotent, so a retry after a crash converges. */
    suspend fun restore(file: KainosBackupFile): BackupRestoreCounts = run(file, apply = true)

    private suspend fun run(file: KainosBackupFile, apply: Boolean): BackupRestoreCounts {
        library.awaitLoaded()
        playlists.awaitLoaded()
        val livePlaylistIds = playlists.playlists.value.mapTo(HashSet()) { it.id } +
            file.playlists.map { it.id }.filter(::isKainosPlaylistId)
        val playlistCounts = playlists.restoreBackupPlaylists(file.playlists, apply)
        val pinCounts = library.restoreBackupPins(file.homePins, file.spotifyAccountId, livePlaylistIds, apply)
        val heartCounts = library.restoreBackupHearts(file.hearts, file.spotifyAccountId, apply)
        val preferencesChanged = settings().backupPreferenceChanges(file.preferences)
        if (apply && preferencesChanged > 0) updateSettings { it.withBackupPreferences(file.preferences) }
        return BackupRestoreCounts(
            heartsAdded = heartCounts.heartsAdded,
            heartsAlready = heartCounts.heartsAlready,
            playlistsAdded = playlistCounts.playlistsAdded,
            playlistsUpdated = playlistCounts.playlistsUpdated,
            playlistsRestored = playlistCounts.playlistsRestored,
            playlistsKept = playlistCounts.playlistsKept,
            pinsAdded = pinCounts.pinsAdded,
            pinsAlready = pinCounts.pinsAlready,
            preferencesChanged = preferencesChanged,
            spotifySkipped = heartCounts.spotifySkipped + pinCounts.spotifySkipped,
        )
    }
}
