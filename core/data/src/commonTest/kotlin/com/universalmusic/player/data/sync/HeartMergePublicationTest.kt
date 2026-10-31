package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.library.UserLibrarySnapshot
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HeartMergePublicationTest {
    private fun track(id: String, key: String) = Track(
        "local:$id", "song.flac", emptyList(), localContentKey = key,
        sources = listOf(PlaybackSource(ProviderId.LOCAL, id,
            streamUrl = "file:///music/song.flac", isPlayable = true, handle = PlaybackHandle.Url("file:///music/song.flac"))),
    )

    @Test
    fun mergePublishesAndPersistsOneDeviceHeartBeforeAnyLaterRematch() = runTest {
        val key = "lc1:" + "a".repeat(64)
        val local = track("phone", key)
        var persisted = UserLibrarySnapshot()
        val store = object : UserLibraryStore {
            override suspend fun read() = persisted
            override suspend fun write(snapshot: UserLibrarySnapshot) { persisted = snapshot }
        }
        val phone = LibraryRepository(store = store, clock = { 10 }, localTrackCatalog = { listOf(local) })
        phone.load(null)
        phone.toggleFavorite(local)
        val pc = LibraryRepository(clock = { 20 })
        pc.toggleFavorite(track("pc", key))
        phone.mergeAndPersistSyncState(pc.exportHeartsSyncDocument())
        assertEquals(setOf(local.canonicalId), phone.favoriteIds.value)
        assertEquals(listOf(local.canonicalId), persisted.favoriteIds)
        // A remote unheart must replace the device heart in that same publication.
        pc.toggleFavorite(track("pc", key))
        phone.mergeAndPersistSyncState(pc.exportHeartsSyncDocument())
        assertEquals(emptySet(), phone.favoriteIds.value)
        assertEquals(emptyList(), persisted.favoriteIds)
        assertEquals(HeartAction.UNFAVORITE, persisted.heartOps.single().action)
    }

    @Test
    fun operationWithoutMetadataRemembersTheMatchedLocalTrackForExport() = runTest {
        val key = "lc1:" + "c".repeat(64)
        val local = track("phone", key)
        val phone = LibraryRepository(localTrackCatalog = { listOf(local) })
        phone.mergeAndPersistSyncState(HeartsSyncDocument("pc", null, ops = listOf(
            HeartOp("localkey:$key", HeartAction.FAVORITE, 20, "pc",
                localIdentity = LocalSyncIdentity(key, "song.flac")),
        )))
        assertEquals(setOf(local.canonicalId), phone.favoriteIds.value)
        assertEquals(listOf(local), phone.savedTracks.value)
        assertEquals("localkey:$key", phone.exportHeartsSyncDocument().ops.single().canonicalId)
    }

    @Test
    fun rememberedDeviceIdentityPreventsDuplicationBeforeScanHydration() = runTest {
        val key = "lc1:" + "b".repeat(64)
        val phone = LibraryRepository(clock = { 10 })
        phone.toggleFavorite(track("phone", key))
        val pc = LibraryRepository(clock = { 20 })
        pc.toggleFavorite(track("pc", key))
        phone.mergeAndPersistSyncState(pc.exportHeartsSyncDocument())
        assertEquals(setOf("local:phone"), phone.favoriteIds.value)
    }
}
