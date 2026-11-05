package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.UserLibrarySnapshot
import com.universalmusic.player.domain.failure.AppFailures
import com.universalmusic.player.domain.failure.StorageArea
import com.universalmusic.player.domain.failure.StorageFailure
import com.universalmusic.player.domain.failure.shown
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private const val STORAGE_MESSAGE =
    "Some saved data could not be loaded or saved. Restart the app to retry. Your database has been kept."

class StorageBoundaryTest {
    @BeforeTest @AfterTest fun clear() = AppFailures.dismiss()

    private fun openedDriver() = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also(KainosDatabase.Schema::create)

    @Test fun failedWriteReachesTheAppScopeAsStorageAndALaterWriteClearsTheMessage() = runTest {
        openedDriver().use { driver ->
            val store = DbUserLibraryStore(KainosStorage(openDriver = { driver }, clock = { 0L }))
            val db = KainosDatabase(driver)
            db.userLibraryQueries.insertRemembered(1, "yt:bad", "not json")

            val failure = assertFailsWith<StorageFailure> {
                store.write(UserLibrarySnapshot(remembered = listOf(PersistedTrack("yt:ok", "OK"))))
            }
            assertEquals(StorageArea.LIBRARY, failure.area)
            AppFailures.report(failure)
            assertEquals(STORAGE_MESSAGE, AppFailures.open.value.shown?.message)

            db.userLibraryQueries.clearRemembered()
            store.write(UserLibrarySnapshot(remembered = listOf(PersistedTrack("yt:ok", "OK"))))
            assertNull(AppFailures.open.value.shown)
        }
    }

    @Test fun successfulWriteInAnotherAreaLeavesTheFailureShowing() = runTest {
        openedDriver().use { driver ->
            AppFailures.report(StorageFailure(StorageArea.LIBRARY, IllegalStateException("locked")))
            DbKainosPlaylistStore(KainosStorage(openDriver = { driver }, clock = { 0L }))
                .write(com.universalmusic.player.data.playlist.KainosPlaylistsSnapshot())
            assertEquals(STORAGE_MESSAGE, AppFailures.open.value.shown?.message)
        }
    }

    @Test fun failedOpenIsTaggedWithTheAreaThatAskedAndKeepsItsMessage() = runTest {
        val store = DbUserLibraryStore(KainosStorage(openDriver = { error("disk gone") }, clock = { 0L }))
        val failure = assertFailsWith<StorageFailure> { store.read() }
        assertEquals(StorageArea.LIBRARY, failure.area)
        assertEquals("disk gone", failure.message)
    }
}
