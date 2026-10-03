package com.universalmusic.player.data.sync

import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFailsWith

class LocalSyncIdentityTest {
    private val a = "lc1:" + "a".repeat(64)
    private val b = "lc1:" + "b".repeat(64)

    @Test
    fun portableIdsValidateKeysAndRetainBasenameFallback() {
        assertEquals("localkey:$a", LocalSyncIdentity(a, "song.flac").portableId)
        assertEquals("localfile:song.flac", LocalSyncIdentity(null, "Song.FLAC").portableId)
        assertEquals(a, localKeyHeartContentKey("localkey:$a"))
        assertNull(localKeyHeartContentKey("localkey:lc1:invalid"))
        assertFailsWith<IllegalArgumentException> { localKeyHeartId("lc1:invalid") }
    }

    @Test
    fun keyMatchesRenamedTrackWhileDifferentSameNamedFileCannotMatch() {
        val renamed = track("renamed", "/music/new-name.flac", a)
        val different = track("different", "/music/song.flac", b)
        val index = LocalSyncIdentityIndex(listOf(renamed, different))
        assertEquals(renamed, index.match(LocalSyncIdentity(a, "song.flac")))
        assertNull(LocalSyncIdentityIndex(listOf(different)).match(LocalSyncIdentity(a, "song.flac")))
    }

    @Test
    fun missingKeysUseUniqueBasenameAndAmbiguousNamesStayUnmatched() {
        val first = track("first", "/one/song.flac", null)
        val second = track("second", "/two/song.flac", null)
        assertEquals(first, LocalSyncIdentityIndex(listOf(first)).match(LocalSyncIdentity(a, "song.flac")))
        assertNull(LocalSyncIdentityIndex(listOf(first, second)).match(LocalSyncIdentity(null, "song.flac")))
    }

    @Test
    fun v1AndV2OpsCollapseToNewestUnheartWithoutMergingDifferentKnownKeys() {
        val ops = listOf(HeartOp("localfile:song.flac", HeartAction.FAVORITE, 10, "old"),
            HeartOp("localkey:$a", HeartAction.UNFAVORITE, 20, "new"))
        val collapsed = ops.collapseLocalSyncAliases(listOf(LocalSyncIdentity(a, "song.flac")))
        assertEquals(1, collapsed.size)
        assertEquals(HeartAction.UNFAVORITE, collapsed.single().action)
        assertEquals("localkey:$a", collapsed.single().canonicalId)
        assertEquals(2, ops.collapseLocalSyncAliases(listOf(LocalSyncIdentity(a, "song.flac"), LocalSyncIdentity(b, "song.flac"))).size)
    }

    private fun track(id: String, location: String, key: String?): Track = Track(
        "local:$id", id, emptyList(), localContentKey = key,
        sources = listOf(PlaybackSource(ProviderId.LOCAL, id, streamUrl = location,
            handle = PlaybackHandle.Url(location), isPlayable = true)),
    )
}
