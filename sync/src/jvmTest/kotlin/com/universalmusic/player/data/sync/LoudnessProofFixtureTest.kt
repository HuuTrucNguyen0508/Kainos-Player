package com.universalmusic.player.data.sync

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.db.DbLocalLibraryScanCache
import com.universalmusic.player.data.db.DbLoudnessStore
import com.universalmusic.player.data.db.KainosDatabase
import com.universalmusic.player.data.db.KainosStorage
import com.universalmusic.player.data.local.LocalLoudnessPass
import com.universalmusic.player.data.local.createLocalLoudnessAnalyzer
import com.universalmusic.player.data.local.loudnessSubject
import com.universalmusic.player.data.loudness.LoudnessRepository
import com.universalmusic.player.domain.playback.VolumeLevelling
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.util.Properties
import kotlin.test.Test

/**
 * Runs the desktop loudness pass against a copy of a real profile database, through the app's
 * own migration, scan cache and store, and prints what it stored. Only runs by hand:
 *
 * KAINOS_LOUDNESS_PROOF_DB=<copy of kainos.db> [KAINOS_LOUDNESS_PROOF_FILES=<count>]
 */
class LoudnessProofFixtureTest {
    @Test
    fun measureACopiedLibrary() = runBlocking<Unit> {
        val path = System.getenv("KAINOS_LOUDNESS_PROOF_DB")
        assumeTrue("Run by hand against a copied database", path != null)
        // The app's own driver setup: opening it migrates the copy to the current schema.
        val storage = KainosStorage(openDriver = {
            JdbcSqliteDriver("jdbc:sqlite:$path", Properties().apply { put("foreign_keys", "true") }, KainosDatabase.Schema)
        }, clock = System::currentTimeMillis)
        val version = storage.read { db -> db.localLibraryQueries.selectTracks().executeAsList().size }
        val scanKey = JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            println("PROOF user_version=${driver.executeQuery(null, "PRAGMA user_version", { c ->
                QueryResult.Value(if (c.next().value) c.getLong(0) else null) }, 0).value} scanRows=$version")
            driver.executeQuery(null, "SELECT config_key FROM local_scan", { c ->
                QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 0).value
        }
        val local = DbLocalLibraryScanCache(storage).read(scanKey!!).orEmpty()
        val count = System.getenv("KAINOS_LOUDNESS_PROOF_FILES")?.toInt() ?: 8
        val subjects = local.map { it.loudnessSubject() }
        val withAlbum = subjects.filter { it.albumKey != null }
        println("PROOF local=${local.size} withAlbumKey=${withAlbum.size} albums=${withAlbum.map { it.albumKey }.distinct().size}")
        val repository = LoudnessRepository(DbLoudnessStore(storage)).apply { load() }
        val picked = (withAlbum.groupBy { it.albumKey }.values.firstOrNull { it.size > 1 }.orEmpty().take(3) + subjects).distinct().take(count)
        val started = System.nanoTime()
        LocalLoudnessPass(createLocalLoudnessAnalyzer(), repository).run(picked)
        println("PROOF measured ${picked.size} in ${(System.nanoTime() - started) / 1_000_000} ms")
        val reloaded = LoudnessRepository(DbLoudnessStore(storage)).apply { load() }
        for (subject in picked) {
            val row = reloaded.stored(subject.location)
            val gain = reloaded.loudnessOf(subject.location)?.let { VolumeLevelling.appliedGainDb(VolumeLevelling.analysed(it.track)) }
            println("PROOF ${subject.fileName} lufs=${row?.track?.integratedLufs} tp=${row?.track?.truePeakDbtp} " +
                "album=${row?.albumKey?.take(24)} albumLufs=${row?.album?.integratedLufs?.let { "%.2f".format(it) }} gain=${gain?.let { "%+.1f".format(it) }}")
        }
    }
}
