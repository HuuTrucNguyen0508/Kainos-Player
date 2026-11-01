package com.universalmusic.player.ui.screens

import com.universalmusic.player.data.local.JvmLocalTrackSource
import com.universalmusic.player.data.local.LocalMusicProvider
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.data.sync.JvmHomeLanVaultStore
import com.universalmusic.player.data.sync.heartedLocalAudioFileNames
import com.universalmusic.player.data.sync.planVaultUnion
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisconnectedLibrarySyncTest {
    @Test fun disconnectPreservesHeartsAndNeverCreatesVaultTombstones() = runTest {
        val home = Files.createTempDirectory("kainos-disconnect-sync")
        try {
            val a = Files.createDirectory(home.resolve("A"))
            val b = Files.createDirectory(home.resolve("B"))
            Files.write(a.resolve("connected.mp3"), byteArrayOf())
            Files.write(b.resolve("hearted.mp3"), byteArrayOf())
            val provider = LocalMusicProvider(JvmLocalTrackSource(listOf(a, b)))
            provider.refresh()
            val library = LibraryRepository(localTrackCatalog = { provider.libraryTracks.value })
            val track = provider.libraryTracks.value.single { it.title == "hearted" }
            library.toggleFavorite(track)
            val heartsBefore = library.exportHeartsSyncDocument()
            val vault = JvmHomeLanVaultStore({ b.toString() }, home.resolve("vault-state.json"))
            val remote = vault.buildIndex("phone")
            assertEquals(1, remote.entries.size)
            Files.move(b, home.resolve("unmounted"))
            Files.write(a.resolve("added.mp3"), byteArrayOf())
            assertEquals(3, provider.refresh().size)
            library.rematchPortableLocalFileHearts(emptyMap(), provider.libraryTracks.value)
            assertTrue(library.isFavorite(track.canonicalId))
            assertEquals(heartsBefore, library.exportHeartsSyncDocument())
            assertEquals(setOf("hearted.mp3"), heartedLocalAudioFileNames(library))
            assertFalse(vault.isConfigured())
            val disconnected = vault.buildIndex("desktop")
            assertTrue(disconnected.entries.isEmpty())
            assertTrue(disconnected.tombstones.isEmpty())
            val plan = planVaultUnion(disconnected, remote)
            assertTrue(plan.applyTombstones.isEmpty())
            assertEquals(remote.entries, plan.copyToLocal)
            assertTrue(vault.loadTombstones().isEmpty())
            Files.move(home.resolve("unmounted"), b)
            val index = vault.buildIndex("desktop")
            assertEquals(1, index.entries.size)
            assertTrue(index.tombstones.isEmpty())
            assertEquals(3, provider.refresh().size)
            assertEquals(heartsBefore, library.exportHeartsSyncDocument())
        } finally { home.toFile().deleteRecursively() }
    }
}
