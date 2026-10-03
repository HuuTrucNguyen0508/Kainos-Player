package com.universalmusic.player.data.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.library.HomePinKind
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedHomePin
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.USER_LIBRARY_FORMAT_VERSION
import com.universalmusic.player.data.library.UserLibrarySnapshot
import com.universalmusic.player.data.local.LocalTrack
import com.universalmusic.player.data.local.StoredLocalTrack
import com.universalmusic.player.data.local.toLocalTrackOrNull
import com.universalmusic.player.data.playlist.KainosPlaylistsSnapshot
import com.universalmusic.player.data.playlist.PersistedKainosPlaylist
import com.universalmusic.player.data.playlist.PersistedPlaylistEntry
import com.universalmusic.player.data.playlist.PlaylistTombstone
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.sync.HeartAction
import com.universalmusic.player.data.sync.HeartOp
import com.universalmusic.player.data.sync.LocalSyncIdentity
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.QualityConfidence
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.RepeatMode
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class DatabaseRoundTripTest {
    @Test
    fun indexedLocalLookupHonorsConfiguredRootsAndDuplicateContent() = runTest {
        val storage = KainosStorage(openDriver = { JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
            KainosDatabase.Schema.create(it)
        } }, clock = { 0 })
        val cache = DbLocalLibraryScanCache(storage)
        val key = "lc1:" + "a".repeat(64)
        val first = LocalTrack("one", "One", location = "/one.flac", contentKey = key)
        val duplicate = first.copy(id = "two", location = "/two.flac")
        cache.write("roots", listOf(first, duplicate))
        assertEquals(listOf(first, duplicate), cache.findByContentKey("roots", key))
        assertTrue(cache.findByContentKey("other-roots", key).isEmpty())
        assertTrue(cache.findByContentKey("roots", "missing").isEmpty())
    }

    @Test
    fun migrationFromInstalledSchemaKeepsExistingHeartsAndAddsIdentityStorage() {
        val directory = Files.createTempDirectory("kainos-v1-migration")
        try {
            val path = directory.resolve("kainos.db")
            javaClass.getResourceAsStream("/1.db")!!.use { Files.copy(it, path) }
            val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
            driver.execute(null, "INSERT INTO heart_op VALUES ('local:old','FAVORITE',10,'pc',NULL)", 0)
            KainosDatabase.Schema.migrate(driver, 1, KainosDatabase.Schema.version)
            val db = KainosDatabase(driver)
            val original = db.userLibraryQueries.selectHeartOps().executeAsOne()
            assertEquals("local:old", original.canonical_id)
            assertNull(original.local_identity_json)
            val identity = LocalSyncIdentity("lc1:" + "a".repeat(64), "song.flac")
            val op = HeartOp("local:new", HeartAction.UNFAVORITE, 20, "pc", localIdentity = identity)
            db.writeUserLibrary(UserLibrarySnapshot(heartOps = listOf(op)))
            assertEquals(op, db.readUserLibrary().heartOps.single())
            driver.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun contentKeyPassOnlyWritesChangedScanRows() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = memoryDatabase(driver)
        val first = LocalTrack("first", "First", location = "/first.flac")
        val second = LocalTrack("second", "Second", location = "/second.flac", contentKey = "lc1:second")
        db.writeLocalScan("folders", listOf(first, second), 1)
        for (event in listOf("UPDATE", "DELETE")) {
            driver.execute(null, "CREATE TRIGGER protect_scan_${event.lowercase()} BEFORE $event ON local_track WHEN OLD.id = 'second' BEGIN SELECT RAISE(ABORT, 'unchanged scan row'); END", 0)
        }
        val keyed = first.copy(contentKey = "lc1:first")
        db.writeLocalScan("folders", listOf(keyed, second), 2)
        assertEquals(listOf(keyed, second), db.readLocalScan("folders"))
        driver.close()
    }

    @Test
    fun changingRecentsDoesNotRewriteFavoritesRememberedOrPins() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = memoryDatabase(driver)
        val first = UserLibrarySnapshot(
            favoriteIds = listOf("yt:favorite"),
            remembered = listOf(track("yt:favorite", "Favorite")),
            recents = listOf(track("yt:old", "Old")),
            homePins = listOf(PersistedHomePin("pin", HomePinKind.KAINOS_PLAYLIST, "playlist", "Playlist")),
        )
        db.writeUserLibrary(first)
        for (table in listOf("favorite", "remembered_track", "home_pin")) {
            for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
                driver.execute(null,
                    "CREATE TRIGGER guard_${table}_${operation} BEFORE $operation ON $table " +
                        "BEGIN SELECT RAISE(ABORT, 'unrelated library row changed'); END", 0)
            }
        }
        val next = first.copy(recents = listOf(track("yt:new", "New")))
        db.writeUserLibrary(next)
        assertEquals(next, db.readUserLibrary())
        driver.close()
    }

    @Test
    fun editingOnePlaylistKeepsOtherPlaylistsAndTheirEntriesUntouched() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val db = memoryDatabase(driver)
        val first = KainosPlaylistsSnapshot(playlists = listOf(
            PersistedKainosPlaylist("edited", "Edited", entries = listOf(PersistedPlaylistEntry("one", track("yt:one", "One")))),
            PersistedKainosPlaylist("untouched", "Untouched", entries = listOf(PersistedPlaylistEntry("two", track("yt:two", "Two")))),
        ))
        db.writeKainosPlaylists(first)
        for ((table, key) in listOf("playlist" to "id", "playlist_entry" to "playlist_id")) {
            for (operation in listOf("UPDATE", "DELETE")) {
                driver.execute(null,
                    "CREATE TRIGGER guard_${table}_${operation} BEFORE $operation ON $table " +
                        "WHEN OLD.$key = 'untouched' BEGIN SELECT RAISE(ABORT, 'unrelated playlist changed'); END", 0)
            }
        }
        val next = first.copy(playlists = listOf(first.playlists[0].copy(
            title = "Renamed", entries = first.playlists[0].entries + PersistedPlaylistEntry("three", track("yt:three", "Three")),
        ), first.playlists[1]))
        db.writeKainosPlaylists(next)
        assertEquals(next, db.readKainosPlaylists())
        driver.close()
    }

    @Test
    fun userLibraryRoundTripPreservesOrderAndSecondWriteReplaces() {
        val db = memoryDatabase()
        assertEquals(UserLibrarySnapshot(), db.readUserLibrary())

        val first = UserLibrarySnapshot(
            version = 1,
            spotifyAccountId = "acct-1",
            favoriteIds = listOf("mm", "aa"),
            heartOps = listOf(
                HeartOp("aa", HeartAction.UNFAVORITE, 2L, "phone", spotifyAccountId = null),
                HeartOp("mm", HeartAction.FAVORITE, 9L, "pc", spotifyAccountId = "acct-1"),
            ),
            remembered = listOf(track("yt:2", "Two"), track("yt:1", "One")),
            recents = listOf(track("local:9", "Recent")),
            homePins = listOf(
                PersistedHomePin(
                    id = "pin:z",
                    kind = HomePinKind.LOCAL_FOLDER,
                    targetId = "/music/z",
                    title = "Zed",
                    subtitle = "folder",
                ),
                PersistedHomePin(
                    id = "pin:a",
                    kind = HomePinKind.KAINOS_PLAYLIST,
                    targetId = "kainos:playlist:a",
                    title = "A",
                    artworkUrl = "https://example/a.jpg",
                ),
            ),
        )
        db.writeUserLibrary(first)
        assertEquals(first.copy(version = USER_LIBRARY_FORMAT_VERSION), db.readUserLibrary())

        val second = UserLibrarySnapshot(favoriteIds = listOf("only"))
        db.writeUserLibrary(second)
        assertEquals(second, db.readUserLibrary())
    }

    @Test
    fun playlistsRoundTripPreservesOrderTombstonesAndSecondWriteReplaces() {
        val db = memoryDatabase()
        val first = KainosPlaylistsSnapshot(
            playlists = listOf(
                PersistedKainosPlaylist(
                    id = "kainos:playlist:b",
                    title = "Bee",
                    entries = listOf(
                        PersistedPlaylistEntry("e1", track("yt:1", "One")),
                        PersistedPlaylistEntry("e2", track("yt:2", "Two")),
                    ),
                    createdAtMs = 10,
                    updatedAtMs = 20,
                    revision = 3,
                    deviceId = "pc",
                ),
                PersistedKainosPlaylist(
                    id = "kainos:playlist:a",
                    title = "Aye",
                    entries = listOf(PersistedPlaylistEntry("e3", track("local:1", "Local"))),
                    createdAtMs = 1,
                    updatedAtMs = 2,
                    revision = 4,
                    deviceId = "phone",
                ),
            ),
            tombstones = listOf(
                PlaylistTombstone("kainos:playlist:gone-a", 5, "pc", 100),
                PlaylistTombstone("kainos:playlist:gone-b", 6, "phone", 200),
            ),
        )
        db.writeKainosPlaylists(first)
        assertEquals(first, db.readKainosPlaylists())

        val second = KainosPlaylistsSnapshot(
            playlists = listOf(
                PersistedKainosPlaylist(id = "kainos:playlist:only", title = "Only", revision = 1, deviceId = "pc"),
            ),
        )
        db.writeKainosPlaylists(second)
        assertEquals(second, db.readKainosPlaylists())
    }

    @Test
    fun sessionRoundTripPreservesShuffleRepeatPositionAndSecondWriteReplaces() {
        val db = memoryDatabase()
        assertEquals(SessionSnapshot(), db.readSessionSnapshot())

        val first = SessionSnapshot(
            items = listOf(
                PersistedQueueItem("q1", track("yt:1", "One")),
                PersistedQueueItem("q2", track("yt:2", "Two")),
                PersistedQueueItem("q3", track("local:3", "Three")),
            ),
            currentIndex = 2,
            shuffle = true,
            repeat = RepeatMode.ALL.name,
            shuffleOrder = listOf(2, 0, 1),
            positionMs = 15_000,
        )
        db.writeSessionSnapshot(first)
        assertEquals(first, db.readSessionSnapshot())

        val second = SessionSnapshot(
            items = listOf(PersistedQueueItem("q9", track("yt:9", "Nine"))),
            currentIndex = 0,
            shuffle = false,
            repeat = RepeatMode.ONE.name,
            positionMs = 4,
        )
        db.writeSessionSnapshot(second)
        assertEquals(second, db.readSessionSnapshot())
    }

    @Test
    fun localScanDistinguishesMissingConfigFromEmptyAndSecondWriteReplaces() {
        val db = memoryDatabase()
        assertNull(db.readLocalScan("folders-a"))

        val flac = LocalTrack(
            id = "id-1",
            title = "Flac",
            artists = listOf("A", "B"),
            album = "Album",
            albumGroupKey = "/music/album",
            durationMs = 180_000,
            artworkUri = "file:///cover.jpg",
            location = "/music/album/song.flac",
            contentLength = 50_000,
            quality = AudioQuality(
                tier = QualityTier.HI_RES,
                codec = "flac",
                bitrateKbps = 2400,
                sampleRateHz = 96_000,
                bitDepth = 24,
                confidence = QualityConfidence.ASSUMED,
            ),
            explicit = true,
            isrc = "USABC",
            fileModifiedEpochMs = 1_700_000_000_000,
            contentKey = "lc1:roundtrip",
        )
        val plain = LocalTrack(id = "id-2", title = "Plain", location = "/music/plain.mp3")
        db.writeLocalScan("folders-a", listOf(flac, plain), nowMs = 50)
        assertEquals(listOf(flac, plain), db.readLocalScan("folders-a"))
        assertNull(db.readLocalScan("folders-b"))
        assertEquals("lc1:roundtrip", db.localLibraryQueries.selectTracks().executeAsList().first().content_key)

        db.writeLocalScan("folders-a", emptyList(), nowMs = 60)
        assertEquals(emptyList(), db.readLocalScan("folders-a"))
        assertNull(db.readLocalScan("other"))

        db.writeLocalScan("folders-b", listOf(plain), nowMs = 70)
        assertNull(db.readLocalScan("folders-a"))
        assertEquals(listOf(plain), db.readLocalScan("folders-b"))
    }

    @Test
    fun clearDropsTheLocalScanRow() = runTest {
        val storage = KainosStorage(
            openDriver = {
                val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
                KainosDatabase.Schema.create(driver)
                driver
            },
            clock = { 1L },
        )
        val cache = DbLocalLibraryScanCache(storage)
        val track = LocalTrack(id = "id-1", title = "Plain", location = "/music/plain.mp3")
        cache.write("folders", listOf(track))
        assertEquals(listOf(track), cache.read("folders"))
        cache.clear()
        assertNull(cache.read("folders"))
    }

    @Test
    fun storedLocalTrackDropsBlankRowsAndUnknownQuality() {
        val blank = StoredLocalTrack(id = " ", title = "T", location = "/x")
        assertNull(blank.toLocalTrackOrNull())
        assertNull(StoredLocalTrack(id = "1", title = " ", location = "/x").toLocalTrackOrNull())
        assertNull(StoredLocalTrack(id = "1", title = "T", location = " ").toLocalTrackOrNull())

        val unknownTier = StoredLocalTrack(
            id = "1",
            title = "T",
            location = "/x",
            qualityTier = "NOT_A_TIER",
            qualityConfidence = "NOPE",
        ).toLocalTrackOrNull()
        assertEquals(null, unknownTier?.quality)

        val unknownConfidence = StoredLocalTrack(
            id = "1",
            title = "T",
            location = "/x",
            qualityTier = QualityTier.HIGH.name,
            qualityCodec = "mp3",
            qualityConfidence = "NOPE",
        ).toLocalTrackOrNull()
        assertEquals(QualityTier.HIGH, unknownConfidence?.quality?.tier)
        assertEquals(QualityConfidence.VERIFIED, unknownConfidence?.quality?.confidence)
        assertEquals("mp3", unknownConfidence?.quality?.codec)
    }
}

class KainosStorageImportTest {
    @Test
    fun importedDataIsReadableAndRetireRunsOnce() = runTest {
        val dir = Files.createTempDirectory("kainos-storage-import")
        try {
            val data = sampleLegacy()
            val legacy = FakeLegacy(data)
            val storage = storage(dir.resolve("kainos.db"), legacy)
            assertEquals(data.userLibrary, storage.read { it.readUserLibrary() })
            assertEquals(data.playlists, storage.read { it.readKainosPlaylists() })
            assertEquals(data.session, storage.read { it.readSessionSnapshot() })
            assertEquals(data.localScanTracks, storage.read { it.readLocalScan(data.localScanConfigKey!!) })
            storage.read { it.readUserLibrary() }
            assertEquals(1, legacy.loads)
            assertEquals(1, legacy.retires)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun newStorageOnTheSameFileDoesNotImportAgain() = runTest {
        val dir = Files.createTempDirectory("kainos-storage-once")
        try {
            val data = sampleLegacy()
            val firstLegacy = FakeLegacy(data)
            val path = dir.resolve("kainos.db")
            storage(path, firstLegacy).read { it.readUserLibrary() }

            val secondLegacy = FakeLegacy(
                LegacyJsonData(userLibrary = UserLibrarySnapshot(favoriteIds = listOf("should-not-import"))),
            )
            val second = storage(path, secondLegacy)
            assertEquals(data.userLibrary, second.read { it.readUserLibrary() })
            assertEquals(0, secondLegacy.loads)
            assertEquals(0, secondLegacy.retires)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun failedImportIsNotMarkedDoneAndTheNextOpenImports() = runTest {
        val dir = Files.createTempDirectory("kainos-storage-retry")
        try {
            val data = sampleLegacy()
            var fail = true
            val legacy = FakeLegacy(data) { fail }
            val path = dir.resolve("kainos.db")
            val first = storage(path, legacy)
            assertFails { first.read { it.readUserLibrary() } }
            assertEquals(1, legacy.loads)
            assertEquals(0, legacy.retires)

            fail = false
            val second = storage(path, legacy)
            assertEquals(data.userLibrary, second.read { it.readUserLibrary() })
            assertEquals(2, legacy.loads)
            assertEquals(1, legacy.retires)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

class JvmLegacyJsonSourceTest {
    @Test
    fun v1UserLibraryImportsFavoritesHeartOpsAndRenamesFiles() = runTest {
        val dir = Files.createTempDirectory("kainos-legacy-json")
        try {
            dir.resolve("user-library.json.migrated").writeText("stale")
            dir.resolve("user-library.json").writeText(
                """{"version":1,"favoriteIds":["yt:song","local:file"]}""",
            )
            dir.resolve("kainos-playlists.json").writeText(
                """{"version":1,"playlists":[{"id":"kainos:playlist:imported","title":"Imported"}]}""",
            )
            val path = dir.resolve("kainos.db")
            val storage = KainosStorage(
                openDriver = { fileDriver(path) },
                legacy = JvmLegacyJsonSource(dir),
                clock = { 7L },
            )
            val store = DbUserLibraryStore(storage)
            val library = LibraryRepository(scope = this, store = store, clock = { 7L })
            library.load(null)

            assertTrue(library.isFavorite("yt:song"))
            assertTrue(library.isFavorite("local:file"))
            val heartOps = library.toSnapshot().heartOps
            assertEquals(listOf("local:file", "yt:song"), heartOps.map { it.canonicalId })
            assertTrue(
                heartOps.all {
                    it.action == HeartAction.FAVORITE &&
                        it.revision == 1L &&
                        it.deviceId == "local" &&
                        it.spotifyAccountId == null
                },
            )
            assertEquals("Imported", DbKainosPlaylistStore(storage).read().playlists.single().title)
            assertEquals(false, Files.exists(dir.resolve("user-library.json")))
            assertEquals(false, Files.exists(dir.resolve("kainos-playlists.json")))
            assertTrue(dir.resolve("user-library.json.migrated").readText().contains("yt:song"))
            assertTrue(dir.resolve("kainos-playlists.json.migrated").readText().contains("Imported"))
            assertEquals(false, Files.exists(dir.resolve("playback-session.json.migrated")))
            assertEquals(false, Files.exists(dir.resolve("local-library-cache.json.migrated")))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}

private fun memoryDatabase(driver: SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)): KainosDatabase {
    KainosDatabase.Schema.create(driver)
    return KainosDatabase(driver)
}

private fun fileDriver(path: Path): SqlDriver {
    Files.createDirectories(path.parent)
    return JdbcSqliteDriver(
        "jdbc:sqlite:$path",
        Properties().apply {
            put("foreign_keys", "true")
            put("journal_mode", "WAL")
        },
        KainosDatabase.Schema,
    )
}

private fun storage(path: Path, legacy: LegacyJsonSource) = KainosStorage(
    openDriver = { fileDriver(path) },
    legacy = legacy,
    clock = { 11L },
)

private fun sampleLegacy(): LegacyJsonData {
    val track = LocalTrack(id = "id-1", title = "Cached", location = "/music/cached.flac")
    return LegacyJsonData(
        userLibrary = UserLibrarySnapshot(
            spotifyAccountId = null,
            favoriteIds = listOf("b", "a"),
            heartOps = listOf(HeartOp("a", HeartAction.FAVORITE, 1L, "pc", spotifyAccountId = null)),
        ),
        playlists = KainosPlaylistsSnapshot(
            playlists = listOf(
                PersistedKainosPlaylist(id = "kainos:playlist:one", title = "One", revision = 1, deviceId = "pc"),
            ),
        ),
        session = SessionSnapshot(
            items = listOf(PersistedQueueItem("q", track("yt:1", "One"))),
            shuffle = true,
            repeat = RepeatMode.ALL.name,
            shuffleOrder = listOf(0),
            positionMs = 8,
        ),
        localScanConfigKey = "mode=explicit",
        localScanTracks = listOf(track),
    )
}

private fun track(id: String, title: String) = PersistedTrack(
    canonicalId = id,
    title = title,
    artists = listOf(PersistedArtist("artist:$id", title)),
    albumCanonicalId = "album:$id",
    albumTitle = "Album",
    durationMs = 1_000,
    artworkUrl = "https://example/$id.jpg",
    explicit = true,
    isrc = "ISRC$id",
    sources = listOf(
        PersistedSource(
            provider = "YOUTUBE_MUSIC",
            providerTrackId = id.substringAfter(':'),
            qualityTier = "HIGH",
            qualityCodec = "opus",
            qualityBitrateKbps = 160,
            qualitySampleRateHz = 48_000,
            qualityConfidence = "VERIFIED",
        ),
    ),
    cachedAtMs = 5,
)

private class FakeLegacy(
    private val data: LegacyJsonData,
    private val failLoad: () -> Boolean = { false },
) : LegacyJsonSource {
    var loads: Int = 0
    var retires: Int = 0

    override suspend fun load(): LegacyJsonData {
        loads += 1
        if (failLoad()) error("legacy load failed")
        return data
    }

    override fun retire() {
        retires += 1
    }
}
