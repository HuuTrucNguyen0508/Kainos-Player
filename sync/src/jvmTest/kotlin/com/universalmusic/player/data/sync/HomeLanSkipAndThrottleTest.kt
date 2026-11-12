package com.universalmusic.player.data.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.db.DbHomeSyncSkipStore
import com.universalmusic.player.data.db.KainosDatabase
import com.universalmusic.player.data.db.KainosStorage
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.playlist.KainosPlaylistRepository
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HomeLanSkipAndThrottleTest {
    private val dbFile = Files.createTempDirectory("kainos-skip-test").resolve("kainos.db")
    private var remoteSizes = mapOf("a.flac" to 100L, "b.flac" to 200L)
    private var now = 1_000_000L
    private var scannedAt: Long? = null
    private var rescans = 0
    private var syncs = 0
    private var duringRescan: suspend () -> Unit = {}
    private val library = LibraryRepository()
    private val vault = tempVaultStore().second
    private var settings = AppSettings(
        homeLanSyncEnabled = true,
        homeLanSyncRole = HomeLanSyncRole.CLIENT,
        homeLanSyncDeviceId = "phone",
        homeLanSyncVaultHeartsOnly = false,
        homeLanSyncHub = PairedPeer(
            "hub",
            "Kainos on pc",
            CertFingerprint.parse("0123456789abcdef".repeat(4))!!,
            "192.168.1.10",
            pairedAtMs = 1L,
        ),
    )

    /** A new store over the same database file, as after a process restart. */
    private fun skipStore() = DbHomeSyncSkipStore(KainosStorage(
        openDriver = {
            JdbcSqliteDriver("jdbc:sqlite:$dbFile").also { driver ->
                val version = driver.executeQuery(null, "PRAGMA user_version", { c ->
                    c.next(); app.cash.sqldelight.db.QueryResult.Value(c.getLong(0))
                }, 0).value
                if (version == 0L) {
                    KainosDatabase.Schema.create(driver)
                    driver.execute(null, "PRAGMA user_version = ${KainosDatabase.Schema.version}", 0)
                }
            }
        },
        clock = { 0 },
    ))

    private fun service(scope: CoroutineScope) = HomeLanSyncService(
        scope = scope,
        settings = { settings },
        updateSettings = { transform -> settings = transform(settings) },
        library = library,
        playlists = KainosPlaylistRepository(),
        hubFactory = { NoOpHomeLanSyncHub() },
        pairingClientFactory = noPairingClient(),
        identity = FixedDeviceIdentity("bb".repeat(32)),
        clientFactory = {
            FakeVaultClient(ByteArray(0), VaultIndexDocument("hub", remoteSizes.map { (name, size) -> VaultFileEntry(name, size, 5L) }))
        },
        vaultStore = vault,
        refreshLocalLibrary = { rescans++; duringRescan() },
        rematchLocalHearts = { syncs++; 0 },
        clock = { now },
        isDesktop = false,
        skipStore = skipStore(),
        lastLocalScanAtMs = { scannedAt },
    ).also { it.hydrateFromSettings(settings) }

    private fun HomeLanSyncService.pending() = status.value.pendingTransfers.map { it.relPath }.sorted()
    private fun HomeLanSyncService.skipped() = status.value.skippedCopies.map { it.subject }.sorted()

    @Test
    fun aSkippedCopyStaysSkippedAfterARestartUntilUnskipped() = runTest {
        val first = service(this)
        first.syncNow().getOrThrow()
        assertEquals(listOf("a.flac", "b.flac"), first.pending())

        first.dismissVaultTransfers(setOf(PendingCopyKey.Vault("a.flac", VaultCopyDirection.TO_LOCAL))).getOrThrow()
        assertEquals(listOf("b.flac"), first.pending())
        assertEquals(listOf("a.flac"), first.skipped())

        val restarted = service(this)
        restarted.syncNow().getOrThrow()
        assertEquals(listOf("b.flac"), restarted.pending())
        assertEquals(listOf("a.flac"), restarted.skipped())
        assertTrue(restarted.status.value.lastDetail.orEmpty().contains("1 skipped"))

        assertEquals("Offered again", restarted.unskipCopy(SkippedCopyKind.VAULT, "a.flac", VaultCopyDirection.TO_LOCAL).getOrThrow())
        assertEquals(listOf("a.flac", "b.flac"), restarted.pending())
        assertEquals(emptyList(), restarted.skipped())

        val again = service(this)
        again.syncNow().getOrThrow()
        assertEquals(listOf("a.flac", "b.flac"), again.pending())
    }

    @Test
    fun skipAllHoldsUntilTheFileChanges() = runTest {
        val first = service(this)
        first.syncNow().getOrThrow()
        first.dismissVaultTransfers().getOrThrow()

        val restarted = service(this)
        restarted.syncNow().getOrThrow()
        assertEquals(emptyList(), restarted.pending())
        assertEquals(listOf("a.flac", "b.flac"), restarted.skipped())

        remoteSizes = mapOf("a.flac" to 101L, "b.flac" to 200L)
        restarted.syncNow().getOrThrow()

        assertEquals(listOf("a.flac"), restarted.pending())
        assertEquals(listOf("b.flac"), restarted.skipped(), "the changed file's old skip is forgotten")
        assertEquals(listOf("b.flac"), skipStore().skipped("hub").map { it.subject })
    }

    @Test
    fun anUnskipWithoutAPlannedCopyWaitsForTheNextSync() = runTest {
        val first = service(this)
        first.syncNow().getOrThrow()
        first.dismissVaultTransfers(setOf(PendingCopyKey.Vault("b.flac", VaultCopyDirection.TO_LOCAL))).getOrThrow()

        val restarted = service(this)
        val result = restarted.unskipCopy(SkippedCopyKind.VAULT, "b.flac", VaultCopyDirection.TO_LOCAL).getOrThrow()

        assertEquals("Offered again at the next sync", result)
        restarted.syncNow().getOrThrow()
        assertEquals(listOf("a.flac", "b.flac"), restarted.pending())
    }

    @Test
    fun syncNowRescansOnlyWhenTheLastScanIsOld() = runTest {
        val service = service(this)
        scannedAt = null
        service.syncNow().getOrThrow()
        assertEquals(1, rescans)

        scannedAt = now - 60_000
        service.syncNow().getOrThrow()
        assertEquals(1, rescans)
        assertTrue(service.status.value.lastDetail.orEmpty().contains("library scan fresh"))

        scannedAt = now - LOCAL_SCAN_FRESH_MS
        service.syncNow().getOrThrow()
        assertEquals(2, rescans)
    }

    @Test
    fun aResumeSyncsAtMostOnceInTenMinutesUnlessSomethingChangedHere() = runTest {
        scannedAt = now
        // The vault walk runs on real IO threads, so foreground work runs on a real scope and is joined.
        val foreground = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        suspend fun settle() = foreground.coroutineContext.job.children.toList().joinAll()
        val service = service(foreground)
        service.onAppForeground()
        settle()
        assertEquals(1, syncs, "the first resume of a process always syncs")

        now += 60_000
        service.onAppForeground()
        settle()
        assertEquals(1, syncs)

        library.toggleFavorite(Track("yt:abc", "A heart", emptyList()))
        service.onAppForeground()
        settle()
        assertEquals(2, syncs, "a heart made here goes out on the next resume")

        now += 60_000
        service.syncNow().getOrThrow()
        assertEquals(3, syncs, "Sync now is never held")

        now += FOREGROUND_SYNC_INTERVAL_MS
        scannedAt = now
        service.onAppForeground()
        settle()
        assertEquals(4, syncs)
        foreground.cancel()
    }

    @Test
    fun aHeartMadeWhileASyncRescansGoesOutOnTheNextResume() = runTest {
        val foreground = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        suspend fun settle() = foreground.coroutineContext.job.children.toList().joinAll()
        val service = service(foreground)
        duringRescan = {
            duringRescan = {}
            library.toggleFavorite(Track("yt:mid", "Hearted mid-sync", emptyList()))
        }
        service.onAppForeground()
        settle()
        assertEquals(1, rescans)
        val afterFirst = syncs

        now += 60_000
        scannedAt = now
        service.onAppForeground()
        settle()
        assertEquals(afterFirst + 1, syncs, "the heart was not in what the first sync sent")
        foreground.cancel()
    }
}

class CopySkipsTest {
    @Test
    fun anotherFileOfTheSameSizeWithoutAHashIsOfferedAgain() = kotlinx.coroutines.test.runTest {
        val skips = CopySkips(MemoryHomeSyncSkipStore()) { 5 }
        // Android sends no content hash; its modification time tells two files apart.
        val skipped = PendingVaultTransfer("a.flac", VaultCopyDirection.TO_REMOTE, 10, mtimeMs = 1_000)
        skips.skip("pc", listOf(skipped), emptyList())
        assertEquals(emptyList(), skips.apply("pc", listOf(skipped), audio = null).vault)

        val replaced = skipped.copy(mtimeMs = 2_000)
        assertEquals(listOf(replaced), skips.apply("pc", listOf(replaced), audio = null).vault)
    }

    @Test
    fun aHashedFileStaysSkippedWhenOnlyItsTimeChanges() = kotlinx.coroutines.test.runTest {
        val skips = CopySkips(MemoryHomeSyncSkipStore()) { 5 }
        val skipped = PendingVaultTransfer("a.flac", VaultCopyDirection.TO_LOCAL, 10, mtimeMs = 1_000, contentHash = "ab12")
        skips.skip("pc", listOf(skipped), emptyList())

        assertEquals(emptyList(), skips.apply("pc", listOf(skipped.copy(mtimeMs = 2_000)), audio = null).vault)
    }

    private fun audio(token: String, size: Long = 10) = PendingCachedAudioTransfer(
        CachedAudioOffer("yt:abc", "abc", token, size, title = "A song"),
        VaultCopyDirection.TO_LOCAL,
    )

    @Test
    fun aNewCacheFileIsOfferedAgain() = kotlinx.coroutines.test.runTest {
        val store = MemoryHomeSyncSkipStore()
        val skips = CopySkips(store) { 5 }
        skips.skip("pc", emptyList(), listOf(audio("t1")))

        val same = skips.apply("pc", vault = null, audio = listOf(audio("t1")))
        assertEquals(emptyList(), same.audio)
        assertEquals(listOf("10:t1"), same.skipped.map { it.fileIdentity })

        val replaced = skips.apply("pc", vault = null, audio = listOf(audio("t2")))
        assertEquals(listOf(audio("t2")), replaced.audio)
        assertEquals(emptyList(), store.skipped("pc"))
    }

    @Test
    fun anUnplannedKindKeepsItsSkips() = kotlinx.coroutines.test.runTest {
        val store = MemoryHomeSyncSkipStore()
        val skips = CopySkips(store) { 5 }
        skips.skip("pc", listOf(PendingVaultTransfer("a.flac", VaultCopyDirection.TO_LOCAL, 10)), listOf(audio("t1")))

        val planned = skips.apply("pc", vault = emptyList(), audio = null)

        assertEquals(listOf(SkippedCopyKind.CACHED_AUDIO), planned.skipped.map { it.kind })
        assertEquals(listOf(SkippedCopyKind.CACHED_AUDIO), store.skipped("pc").map { it.kind })
    }
}
