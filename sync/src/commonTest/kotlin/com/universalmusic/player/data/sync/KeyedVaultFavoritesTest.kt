package com.universalmusic.player.data.sync

import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class KeyedVaultFavoritesTest {
    @Test
    fun pendingKeyedHeartStillAllowsItsMissingVaultFileToTransfer() = runTest {
        val source = LibraryRepository()
        source.toggleFavorite(Track("local:phone", "Song", emptyList(), localContentKey = "lc1:" + "a".repeat(64),
            sources = listOf(PlaybackSource(ProviderId.LOCAL, "phone", isPlayable = true,
                handle = PlaybackHandle.Url("file:///music/song.flac")))))
        val target = LibraryRepository()
        target.mergeAndPersistSyncState(source.exportHeartsSyncDocument())
        assertEquals(setOf("song.flac"), heartedLocalAudioFileNames(target))
    }
}
