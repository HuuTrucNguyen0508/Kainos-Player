package com.universalmusic.player.data.library

import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Regression: a Home sync merge that beat startup saved an empty library over Android play history. */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryLoadGateTest {
    private val history = listOf(
        track("Aphelion", "Crywolf", provider = ProviderId.LOCAL),
        track("alright", "Raph", provider = ProviderId.LOCAL),
    )

    @Test
    fun changesBeforeLoadNeverOverwriteTheSavedLibrary() = runTest {
        val store = GatedStore(
            UserLibrarySnapshot(recents = history.map { it.toPersisted(1L) }),
        )
        val library = LibraryRepository(scope = backgroundScope, store = store, clock = { 2L })

        // Startup race: something changes the library while load() is still reading the file.
        val loading = async { library.load(activeSpotifyAccountId = null) }
        runCurrent()
        library.recordPlay(track("Early tap", "Someone", provider = ProviderId.LOCAL))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, store.writes, "nothing may be saved before the library has loaded")

        store.release.complete(Unit)
        loading.await()
        advanceUntilIdle()

        assertEquals(history.map { it.title }, library.recentlyPlayed.value.map { it.title })
        assertTrue(store.saved.recents.map { it.title }.containsAll(history.map { it.title }))
    }

    @Test
    fun awaitLoadedSuspendsUntilLoadFinishes() = runTest {
        val store = GatedStore(UserLibrarySnapshot())
        val library = LibraryRepository(scope = backgroundScope, store = store)
        val waiter = async { library.awaitLoaded() }
        runCurrent()
        assertFalse(waiter.isCompleted)

        store.release.complete(Unit)
        library.load(activeSpotifyAccountId = null)
        runCurrent()
        assertTrue(waiter.isCompleted)
    }

    @Test
    fun storelessLibraryCountsAsLoaded() = runTest {
        LibraryRepository().awaitLoaded()
    }
}

/** Store whose first read blocks until [release], like a slow disk at app start. */
private class GatedStore(initial: UserLibrarySnapshot) : UserLibraryStore {
    val release = CompletableDeferred<Unit>()
    var saved: UserLibrarySnapshot = initial
        private set
    var writes = 0
        private set

    override suspend fun read(): UserLibrarySnapshot {
        release.await()
        return saved
    }

    override suspend fun write(snapshot: UserLibrarySnapshot) {
        writes++
        saved = snapshot
    }
}
