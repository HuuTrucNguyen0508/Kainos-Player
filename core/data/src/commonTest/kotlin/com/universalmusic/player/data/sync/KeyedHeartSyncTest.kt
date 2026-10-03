package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.library.UserLibrarySnapshot
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class KeyedHeartSyncTest {
    private val key = "lc1:" + "a".repeat(64)
    private val other = "lc1:" + "b".repeat(64)

    @Test
    fun latestScanSuppliesKeysRecordedAfterHeartAndV1DowngradeKeepsUnheart() {
        var catalog = listOf(track("phone", "song.flac", null))
        var now = 10L
        val repo = LibraryRepository(clock = { now }, localTrackCatalog = { catalog })
        repo.toggleFavorite(catalog.single())
        catalog = listOf(catalog.single().copy(localContentKey = key))
        val keyed = repo.exportHeartsSyncDocument()
        assertEquals("localkey:$key", keyed.ops.single().canonicalId)
        assertEquals("localkey:$key", keyed.favoritesMetadata.single().canonicalId)
        now = 20
        repo.toggleFavorite(catalog.single())
        val tombstone = repo.exportHeartsSyncDocument()
        assertEquals(HeartAction.UNFAVORITE, tombstone.ops.single().action)
        assertEquals("localfile:song.flac", tombstone.forV1Sync().ops.single().canonicalId)
        assertNull(tombstone.forV1Sync().ops.single().localIdentity)
        assertEquals(20L, tombstone.forV1Sync().ops.single().revision)
    }

    @Test
    fun renamedKeyedFileRematchesAndUnheartSurvivesRestart() = runTest {
        var now = 10L
        val phoneTrack = track("phone", "song.flac", key)
        val pcTrack = track("pc", "renamed.flac", key)
        val phone = LibraryRepository(clock = { now }, deviceIdProvider = { "phone" })
        val pc = LibraryRepository(clock = { now }, deviceIdProvider = { "pc" })
        phone.toggleFavorite(phoneTrack)
        pc.mergeAndPersistSyncState(phone.exportHeartsSyncDocument())
        pc.rematchPortableLocalFileHearts(emptyMap(), listOf(pcTrack))
        assertTrue(pc.isFavorite(pcTrack.canonicalId))
        now = 20
        phone.toggleFavorite(phoneTrack)
        pc.mergeAndPersistSyncState(phone.exportHeartsSyncDocument())
        pc.rematchPortableLocalFileHearts(emptyMap(), listOf(pcTrack))
        assertFalse(pc.isFavorite(pcTrack.canonicalId))
        val restarted = LibraryRepository(clock = { now })
        restarted.applySnapshot(pc.toSnapshot())
        assertEquals(HeartAction.UNFAVORITE, restarted.exportHeartsSyncDocument().ops.single().action)
        assertEquals("localfile:renamed.flac", restarted.exportHeartsSyncDocument().forV1Sync().ops.single().canonicalId)
    }

    @Test
    fun oldAndNewPeerAliasesCollapseAndDifferentKnownFileDoesNotMatch() = runTest {
        val local = track("pc", "song.flac", key)
        val repo = LibraryRepository(clock = { 10 }, localTrackCatalog = { listOf(local) })
        repo.toggleFavorite(local)
        repo.mergeAndPersistSyncState(HeartsSyncDocument("old", null, ops = listOf(
            HeartOp("localfile:song.flac", HeartAction.UNFAVORITE, 20, "old"))))
        repo.rematchPortableLocalFileHearts(emptyMap(), listOf(local))
        assertFalse(repo.isFavorite(local.canonicalId))
        assertEquals(1, repo.exportHeartsSyncDocument().ops.size)
        assertEquals("localkey:$key", repo.exportHeartsSyncDocument().ops.single().canonicalId)
        val mismatched = LibraryRepository()
        mismatched.mergeAndPersistSyncState(HeartsSyncDocument("phone", null, ops = listOf(
            HeartOp("localkey:$other", HeartAction.FAVORITE, 30, "phone", localIdentity = LocalSyncIdentity(other, "song.flac")))))
        assertEquals(0, mismatched.rematchPortableLocalFileHearts(emptyMap(), listOf(local)))
        assertFalse(mismatched.isFavorite(local.canonicalId))
    }

    @Test
    fun rematchingDeviceRenamePreservesRevisionInsteadOfRecordingUnheart() = runTest {
        val old = track("old", "song.flac", key)
        val renamed = track("new", "renamed.flac", key)
        val repo = LibraryRepository(clock = { 10 })
        repo.toggleFavorite(old)
        repo.rematchPortableLocalFileHearts(emptyMap(), listOf(renamed))
        assertFalse(repo.isFavorite(old.canonicalId))
        assertTrue(repo.isFavorite(renamed.canonicalId))
        assertEquals(10L, repo.exportHeartsSyncDocument().ops.single().revision)
        assertEquals(HeartAction.FAVORITE, repo.exportHeartsSyncDocument().ops.single().action)
    }

    @Test
    fun downgradeSkipsAmbiguousDifferentKeysWithTheSameBasename() {
        val doc = HeartsSyncDocument("pc", null, ops = listOf(
            HeartOp("localkey:$key", HeartAction.FAVORITE, 10, "pc", localIdentity = LocalSyncIdentity(key, "song.flac")),
            HeartOp("localkey:$other", HeartAction.UNFAVORITE, 20, "pc", localIdentity = LocalSyncIdentity(other, "song.flac")),
        ))
        assertTrue(doc.forV1Sync().ops.isEmpty())
    }

    private fun track(id: String, name: String, key: String?) = Track("local:$id", name, emptyList(),
        localContentKey = key, sources = listOf(PlaybackSource(ProviderId.LOCAL, id,
            streamUrl = "file:///music/$name", handle = PlaybackHandle.Url("file:///music/$name"), isPlayable = true)))
}
