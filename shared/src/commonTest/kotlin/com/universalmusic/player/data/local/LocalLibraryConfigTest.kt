package com.universalmusic.player.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LocalLibraryConfigTest {
    @Test
    fun effectiveFoldersDistinguishDefaultsFromExplicitEmpty() {
        assertEquals(
            listOf("/home/u/Music"),
            LocalLibraryScanConfig(mode = LocalLibraryRootMode.USE_DEFAULTS)
                .effectiveFolders("/home/u/Music"),
        )
        assertTrue(
            LocalLibraryScanConfig(mode = LocalLibraryRootMode.EXPLICIT, folders = emptyList())
                .effectiveFolders("/home/u/Music")
                .isEmpty(),
        )
        assertEquals(
            listOf("/a", "/b"),
            LocalLibraryScanConfig(
                mode = LocalLibraryRootMode.EXPLICIT,
                folders = listOf("/a", " ", "/b", "/a"),
            ).effectiveFolders("/home/u/Music"),
        )
    }

    @Test
    fun albumCanonicalIdsDifferForSameTitleDifferentGroupKeys() {
        val left = localAlbumCanonicalId("Greatest Hits", listOf("Artist A"), "/music/a/Greatest Hits")
        val right = localAlbumCanonicalId("Greatest Hits", listOf("Artist B"), "/music/b/Greatest Hits")
        assertNotEquals(left, right)
        assertEquals(
            localAlbumCanonicalId("Greatest Hits", listOf("Artist A"), "/music/a/Greatest Hits"),
            left,
        )
    }

    @Test
    fun compositeSourceDedupesOverlappingTracksPreferringFirst() = runTest {
        val first = LocalTrackSource {
            listOf(
                LocalTrack(
                    id = "saf-1",
                    title = "Overlap",
                    artists = listOf("Band"),
                    album = "LP",
                    durationMs = 1000,
                    location = "content://saf/1",
                    contentLength = 42,
                ),
            )
        }
        val second = LocalTrackSource {
            listOf(
                LocalTrack(
                    id = "ms-1",
                    title = "Overlap",
                    artists = listOf("Band"),
                    album = "LP",
                    durationMs = 1000,
                    location = "content://media/1",
                    contentLength = 42,
                ),
                LocalTrack(
                    id = "ms-2",
                    title = "Only MediaStore",
                    location = "content://media/2",
                ),
            )
        }
        val merged = CompositeLocalTrackSource(first, second).scan()
        assertEquals(2, merged.size)
        assertEquals("saf-1", merged.first().id)
        assertEquals("ms-2", merged.last().id)
    }
}
