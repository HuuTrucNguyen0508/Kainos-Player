package com.universalmusic.player.data.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.universalmusic.player.data.library.UserLibrarySnapshot
import com.universalmusic.player.data.local.LocalTrack
import com.universalmusic.player.data.playlist.KainosPlaylistsSnapshot
import com.universalmusic.player.data.session.SessionSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val LEGACY_JSON_IMPORT_STEP = "legacy-json-import"

/** Everything the pre-database builds kept in JSON files. Null means that file was absent. */
data class LegacyJsonData(
    val userLibrary: UserLibrarySnapshot? = null,
    val playlists: KainosPlaylistsSnapshot? = null,
    val session: SessionSnapshot? = null,
    val localScanConfigKey: String? = null,
    val localScanTracks: List<LocalTrack> = emptyList(),
)

interface LegacyJsonSource {
    suspend fun load(): LegacyJsonData

    /** Called once the import has committed; renames the files so they are kept for rollback. */
    fun retire()
}

/**
 * Opens the single app database on first use and runs the one-time import of the
 * pre-database JSON files before anything else can read or write.
 *
 * The import and the "done" marker commit in one transaction, so a crash mid-import leaves
 * no half-imported data and the next launch imports again.
 */
class KainosStorage(
    private val openDriver: () -> SqlDriver,
    private val legacy: LegacyJsonSource? = null,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    private var database: KainosDatabase? = null

    suspend fun <T> read(block: (KainosDatabase) -> T): T {
        val db = database()
        return withContext(Dispatchers.IO) { db.transactionWithResult { block(db) } }
    }

    /** Runs [block] in one transaction: readers never see a half-written snapshot. */
    suspend fun <T> write(block: (KainosDatabase) -> T): T {
        val db = database()
        return withContext(Dispatchers.IO) { db.transactionWithResult { block(db) } }
    }

    private suspend fun database(): KainosDatabase = mutex.withLock {
        database ?: open().also { database = it }
    }

    private suspend fun open(): KainosDatabase = withContext(Dispatchers.IO) {
        val driver = openDriver()
        val version = driver.executeQuery(
            null,
            "PRAGMA user_version",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else 0L) },
            0,
        ).value
        // Only Android's helper refuses this itself; an older build must not write over newer data.
        if (version != null && version > KainosDatabase.Schema.version) {
            driver.close()
            error("kainos.db is from a newer build (version $version)")
        }
        val db = KainosDatabase(driver)
        if (!db.storageMetaQueries.isDone(LEGACY_JSON_IMPORT_STEP).executeAsOne()) {
            val data = legacy?.load() ?: LegacyJsonData()
            db.transaction {
                data.userLibrary?.let(db::writeUserLibrary)
                data.playlists?.let(db::writeKainosPlaylists)
                data.session?.let { db.writeSessionSnapshot(it, cached = null) }
                data.localScanConfigKey?.let { db.writeLocalScan(it, data.localScanTracks, clock()) }
                db.storageMetaQueries.markDone(LEGACY_JSON_IMPORT_STEP, clock())
            }
            legacy?.let { runCatching(it::retire) }
        }
        db
    }
}
