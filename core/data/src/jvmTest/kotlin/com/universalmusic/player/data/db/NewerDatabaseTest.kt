package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import kotlin.test.Test
import com.universalmusic.player.domain.failure.AppFailures
import com.universalmusic.player.domain.failure.shown
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewerDatabaseTest {
    @Test
    fun databaseFromANewerBuildIsRefusedAndLeftUntouched() = runTest {
        val path = Files.createTempDirectory("kainos-newer-db").resolve("kainos.db")
        val url = "jdbc:sqlite:$path"
        JdbcSqliteDriver(url, Properties(), KainosDatabase.Schema).use { it.execute(null, "PRAGMA user_version = 999", 0) }
        val before = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))

        val storage = KainosStorage(
            openDriver = { JdbcSqliteDriver(url, Properties(), KainosDatabase.Schema) },
            legacy = null,
            clock = { 1L },
        )
        val failure = runCatching { DbUserLibraryStore(storage).read() }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("newer"), "got $failure")
        AppFailures.dismiss()
        AppFailures.report(failure!!)
        assertEquals(
            "This database was written by a newer Kainos build; run that build or restore a backup.",
            AppFailures.open.value.shown?.message,
            "Restarting cannot fix it, so the notice must not say so",
        )
        AppFailures.dismiss()
        assertContentEquals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)))
    }
}
