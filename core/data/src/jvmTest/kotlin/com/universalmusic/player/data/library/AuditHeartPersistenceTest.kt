package com.universalmusic.player.data.library

import com.universalmusic.player.data.cache.*
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.Artwork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.*
import kotlin.io.path.createTempDirectory
import kotlin.test.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class AuditHeartPersistenceTest {
    @Test fun stalledArtworkDoesNotDelayHeartPersistence() = runTest {
        val root = createTempDirectory()
        try {
            var saved = UserLibrarySnapshot()
            val store = object : UserLibraryStore {
                override suspend fun read() = saved
                override suspend fun write(snapshot: UserLibrarySnapshot) { saved = snapshot }
            }
            val gate = CompletableDeferred<ByteArray?>()
            val cache = DefaultMetadataArtworkCache(FileMetadataCacheDisk(root), { gate.await() })
            val repo = LibraryRepository(backgroundScope, store, cache)
            repo.load(null)
            repo.toggleFavorite(Track("yt:a", "A", emptyList(), artwork = Artwork("https://example.test/a")))
            runCurrent()
            advanceTimeBy(300)
            runCurrent()
            assertEquals(listOf("yt:a"), saved.favoriteIds)
            assertEquals("yt:a", saved.remembered.single().canonicalId)
            gate.complete(null)
            runCurrent()
        } finally { root.toFile().deleteRecursively() }
    }
}
