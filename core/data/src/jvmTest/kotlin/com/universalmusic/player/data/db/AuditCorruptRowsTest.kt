package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.domain.failure.AppFailures
import com.universalmusic.player.domain.failure.shown
import com.universalmusic.player.data.library.UserLibrarySnapshot
import com.universalmusic.player.data.playlist.KainosPlaylistsSnapshot
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class AuditCorruptRowsTest {
    @BeforeTest @AfterTest fun clearFailures() = AppFailures.dismiss()

    @Test fun badRememberedRowDoesNotHideGoodRowsOrGetOverwritten() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            KainosDatabase.Schema.create(driver)
            val db = KainosDatabase(driver)
            db.writeUserLibrary(UserLibrarySnapshot(remembered = listOf(PersistedTrack("yt:ok", "OK"))))
            db.userLibraryQueries.insertRemembered(1, "yt:bad", "not json")
            assertEquals(listOf("yt:ok"), db.readUserLibrary().remembered.map { it.canonicalId })
            assertEquals(
                "Some saved data could not be loaded or saved. Restart the app to retry. Your database has been kept.",
                AppFailures.open.value.shown?.message,
            )
            assertFails { db.writeUserLibrary(UserLibrarySnapshot()) }
            assertEquals(2, db.userLibraryQueries.selectRemembered().executeAsList().size)
        }
    }
    @Test fun badPlaylistEntryDoesNotHideOtherPlaylists() {
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).use { driver ->
            KainosDatabase.Schema.create(driver)
            val db = KainosDatabase(driver)
            db.kainosPlaylistsQueries.insertPlaylist("kainos:test", 0, "Keep", 0, 0, 1, "device")
            db.kainosPlaylistsQueries.insertEntry("kainos:test", 0, "entry", "yt:bad", "not json")
            assertEquals("Keep", db.readKainosPlaylists().playlists.single().title)
            assertTrue(db.readKainosPlaylists().playlists.single().entries.isEmpty())
            assertFails { db.writeKainosPlaylists(KainosPlaylistsSnapshot()) }
            assertEquals(1, db.kainosPlaylistsQueries.selectEntries().executeAsList().size)
        }
    }
    @Test fun failedOpenCanRetryWithoutResettingData() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KainosDatabase.Schema.create(driver)
        KainosDatabase(driver).writeUserLibrary(UserLibrarySnapshot(favoriteIds = listOf("yt:keep")))
        var fail = true
        val storage = KainosStorage(openDriver = {
            if (fail) error("Can't downgrade database")
            driver
        }, clock = { 1 })
        try {
            assertFails { DbUserLibraryStore(storage).read() }
            fail = false
            assertEquals(listOf("yt:keep"), DbUserLibraryStore(storage).read().favoriteIds)
        } finally { driver.close() }
    }
}
