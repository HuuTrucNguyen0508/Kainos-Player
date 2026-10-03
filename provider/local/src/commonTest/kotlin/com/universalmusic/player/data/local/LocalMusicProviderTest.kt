package com.universalmusic.player.data.local

import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.ProviderState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalMusicProviderTest {
    @Test
    fun keysReuseOnlyUnchangedFingerprintsAndReachPersistedMetadata() = runTest {
        var snapshot = listOf(LocalTrack("key", "Track", location = "/track", contentLength = 8, fileModifiedEpochMs = 1))
        var reads = 0
        val provider = LocalMusicProvider(
            source = LocalTrackSource { snapshot },
            contentKeyReader = LocalContentKeyReader { reads++; "lc1:key$reads" },
        )
        provider.refresh()
        assertEquals(0, reads)
        provider.enrichContentKeys()
        assertEquals("lc1:key1", provider.libraryTracks.value.single().localContentKey)
        val persisted = provider.libraryTracks.value.single().toPersisted(0)
        assertEquals("lc1:key1", persisted.toDomain().localContentKey)
        provider.refresh()
        provider.enrichContentKeys()
        assertEquals(1, reads)
        snapshot = listOf(snapshot.single().copy(fileModifiedEpochMs = 2))
        provider.refresh()
        assertNull(provider.libraryTracks.value.single().localContentKey)
        provider.enrichContentKeys()
        assertEquals("lc1:key2", provider.libraryTracks.value.single().localContentKey)
    }

    @Test
    fun keyReadDoesNotAttachToAChangedScan() = runTest {
        var snapshot = listOf(LocalTrack("key", "Track", location = "/track", contentLength = 8, fileModifiedEpochMs = 1))
        lateinit var provider: LocalMusicProvider
        provider = LocalMusicProvider(
            source = LocalTrackSource { snapshot },
            contentKeyReader = LocalContentKeyReader {
                snapshot = listOf(snapshot.single().copy(fileModifiedEpochMs = 2))
                provider.refresh()
                "lc1:obsolete"
            },
        )
        provider.refresh()
        provider.enrichContentKeys()
        assertNull(provider.libraryTracks.value.single().localContentKey)
    }

    @Test
    fun refreshMapsThePlatformSnapshotIntoPlayableLibraryTracks() = runTest {
        val provider = LocalMusicProvider(
            LocalTrackSource {
                listOf(
                    localTrack(
                        id = "42",
                        title = "Night Drive",
                        location = "content://media/external/audio/media/42",
                    ),
                    localTrack(
                        id = "path-track",
                        title = "Morning Walk",
                        location = "/music/Morning Walk.flac",
                    ),
                )
            },
        )

        val refreshed = provider.refresh()

        assertEquals(refreshed, provider.getLibraryTracks())
        assertEquals(ProviderState.AVAILABLE, provider.state.value)
        assertEquals(ProviderId.LOCAL, refreshed.first().sourceFor(ProviderId.LOCAL)?.provider)
        assertEquals(
            PlaybackHandle.Url("content://media/external/audio/media/42"),
            provider.getStream(refreshed.first())?.handle,
        )
        assertEquals(
            PlaybackHandle.Url("/music/Morning Walk.flac"),
            provider.getStream(refreshed.last())?.handle,
        )
    }

    @Test
    fun searchAndLookupUseTheRefreshedSnapshot() = runTest {
        val provider = LocalMusicProvider(
            LocalTrackSource {
                listOf(
                    localTrack(id = "one", title = "Blue Hour", artists = listOf("Signal Club"), album = "City Lines"),
                    localTrack(id = "two", title = "Home", artists = listOf("Northbound"), album = "Quiet Rooms"),
                )
            },
        )
        provider.refresh()

        assertEquals(listOf("local:one"), provider.search("signal").tracks.map { it.canonicalId })
        assertEquals(listOf("local:two"), provider.search("quiet rooms").tracks.map { it.canonicalId })
        assertTrue(provider.search("  ").tracks.isEmpty())
        assertEquals("local:one", provider.getTrack("one")?.canonicalId)
        assertEquals("local:one", provider.getTrack("local:one")?.canonicalId)
        assertNull(provider.getTrack("missing"))
    }

    @Test
    fun failedRefreshKeepsThePreviousSnapshotAndMarksProviderUnavailable() = runTest {
        var fail = false
        val provider = LocalMusicProvider(
            LocalTrackSource {
                if (fail) throw IllegalStateException("media permission revoked")
                listOf(localTrack(id = "one", title = "Still Here"))
            },
        )
        provider.refresh()
        fail = true

        assertFailsWith<IllegalStateException> { provider.refresh() }
        assertEquals(ProviderState.UNAVAILABLE, provider.state.value)
        assertEquals(listOf("local:one"), provider.getLibraryTracks().map { it.canonicalId })
    }

    @Test
    fun capabilitiesDescribeAProviderWithoutRemoteOrPlaylistFeatures() = runTest {
        val capabilities = LocalMusicProvider(LocalTrackSource { emptyList() }).getCapabilities()

        assertTrue(capabilities.search)
        assertTrue(capabilities.metadata)
        assertTrue(capabilities.library)
        assertTrue(capabilities.playback)
        assertTrue(capabilities.backgroundPlayback)
        assertTrue(capabilities.losslessPlayback)
        assertEquals(false, capabilities.playlists)
    }

    @Test
    fun sameAlbumTitleDifferentGroupKeysMapToDistinctAlbumRefs() = runTest {
        val provider = LocalMusicProvider(
            LocalTrackSource {
                listOf(
                    localTrack(
                        id = "one",
                        title = "A",
                        album = "Greatest Hits",
                        artists = listOf("Artist A"),
                        albumGroupKey = "/music/a/Greatest Hits",
                    ),
                    localTrack(
                        id = "two",
                        title = "B",
                        album = "Greatest Hits",
                        artists = listOf("Artist B"),
                        albumGroupKey = "/music/b/Greatest Hits",
                    ),
                )
            },
        )
        val tracks = provider.refresh()
        assertNotEquals(tracks[0].album?.canonicalId, tracks[1].album?.canonicalId)
    }

    @Test
    fun embeddedArtworkIsExtractedLazilyAndPersistedToTheScanCache() = runTest {
        val extractor = FakeEmbeddedArtwork(pictures = mapOf("flac" to "file:/art/flac.jpg"))
        val cache = InMemoryScanCache()
        val provider = LocalMusicProvider(
            LocalTrackSource {
                listOf(
                    localTrack(id = "flac", title = "Embedded only", location = "content://docs/flac"),
                    localTrack(id = "bare", title = "No picture", location = "content://docs/bare"),
                )
            },
            cache = cache,
            configKey = { "k" },
            embeddedArtwork = extractor,
        )
        val scanned = provider.refresh()
        assertNull(scanned.first().artwork, "scan must not open files")

        val resolved = provider.resolveEmbeddedArtwork(scanned.first())
        assertEquals("file:/art/flac.jpg", resolved?.url)
        assertEquals("file:/art/flac.jpg", provider.getLibraryTracks().first().artwork?.url)
        assertEquals("file:/art/flac.jpg", cache.read("k")?.first()?.artworkUri)

        provider.enrichEmbeddedArtwork()
        assertEquals(listOf("flac", "bare"), extractor.probed, "each file probed once")
        assertNull(provider.getLibraryTracks().last().artwork)

        // A later hydrate picks the extracted cover up without touching the file again.
        val again = LocalMusicProvider(
            LocalTrackSource { emptyList() },
            cache = cache,
            configKey = { "k" },
            embeddedArtwork = extractor,
        )
        again.hydrateFromCache()
        assertEquals("file:/art/flac.jpg", again.getLibraryTracks().first().artwork?.url)
        assertEquals(listOf("flac", "bare"), extractor.probed)
    }
}

private class FakeEmbeddedArtwork(
    private val pictures: Map<String, String>,
) : LocalEmbeddedArtworkExtractor {
    val probed = mutableListOf<String>()
    private val extracted = mutableMapOf<String, String>()

    override fun cachedArtworkUri(trackId: String): String? = extracted[trackId]

    override suspend fun extractArtworkUri(trackId: String, location: String): String? {
        extracted[trackId]?.let { return it }
        probed += trackId
        return pictures[trackId]?.also { extracted[trackId] = it }
    }
}

private class InMemoryScanCache : LocalLibraryScanCache {
    private val entries = mutableMapOf<String, List<LocalTrack>>()
    override suspend fun read(configKey: String): List<LocalTrack>? = entries[configKey]
    override suspend fun write(configKey: String, tracks: List<LocalTrack>) {
        entries[configKey] = tracks
    }
}

private fun localTrack(
    id: String,
    title: String,
    artists: List<String> = listOf("Local Artist"),
    album: String? = "Local Album",
    location: String = "/music/$id.mp3",
    albumGroupKey: String = "",
): LocalTrack = LocalTrack(
    id = id,
    title = title,
    artists = artists,
    album = album,
    albumGroupKey = albumGroupKey,
    durationMs = 180_000,
    location = location,
)
