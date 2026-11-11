package com.universalmusic.player.data.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.universalmusic.player.data.sync.SkippedCopy
import com.universalmusic.player.data.sync.SkippedCopyKind
import com.universalmusic.player.data.sync.VaultCopyDirection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HomeSyncSkipStoreTest {
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { KainosDatabase.Schema.create(it) }
    private fun store() = DbHomeSyncSkipStore(KainosStorage(openDriver = { driver }, clock = { 0 }))

    private fun skip(subject: String, identity: String = "10:abc", at: Long = 1, peer: String = "pc") = SkippedCopy(
        peerDeviceId = peer,
        kind = SkippedCopyKind.VAULT,
        subject = subject,
        direction = VaultCopyDirection.TO_LOCAL,
        fileIdentity = identity,
        label = "Download $subject",
        sizeBytes = 10,
        skippedAtMs = at,
    )

    @Test
    fun skipsSurviveARestartPerPeerNewestFirst() = runTest {
        store().skip(listOf(skip("a.flac", at = 1), skip("b.flac", at = 2), skip("c.flac", peer = "other")))

        assertEquals(listOf("b.flac", "a.flac"), store().skipped("pc").map { it.subject })
        assertEquals(skip("a.flac", at = 1), store().skipped("pc").last())
    }

    @Test
    fun skippingAgainReplacesTheFileIdentity() = runTest {
        val store = store()
        store.skip(listOf(skip("a.flac", identity = "10:old")))
        store.skip(listOf(skip("a.flac", identity = "12:new", at = 5)))

        assertEquals(listOf("12:new"), store.skipped("pc").map { it.fileIdentity })
    }

    @Test
    fun unskipRemovesOnlyThatCopy() = runTest {
        val store = store()
        store.skip(listOf(skip("a.flac"), skip("b.flac")))

        store.unskip("pc", SkippedCopyKind.VAULT, "a.flac", VaultCopyDirection.TO_LOCAL)
        store.unskip("pc", SkippedCopyKind.VAULT, "b.flac", VaultCopyDirection.TO_REMOTE)

        assertEquals(listOf("b.flac"), store().skipped("pc").map { it.subject })
    }
}
