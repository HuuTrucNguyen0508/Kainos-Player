package com.universalmusic.player.data.local

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalFolderMatchTest {
    @Test
    fun desktopFileUriInsideRootMatches() {
        assertTrue(isLocationInFolder("file:///home/me/Music/Rock/a.flac", "/home/me/Music/Rock"))
        assertTrue(isLocationInFolder("file:///home/me/Music/Rock/deep/b.flac", "/home/me/Music/Rock/"))
    }

    @Test
    fun siblingFolderWithSamePrefixDoesNotMatch() {
        assertFalse(isLocationInFolder("file:///home/me/Music/Rock%20Classics/a.flac", "/home/me/Music/Rock"))
        assertFalse(isLocationInFolder("file:///other/home/me/Music/Rock/a.flac", "/home/me/Music/Rock"))
    }

    @Test
    fun percentEncodedSpacesAndUnicodeDecode() {
        assertTrue(isLocationInFolder("file:///home/me/My%20Music/Caf%C3%A9/a.flac", "/home/me/My Music/Café"))
        // '+' is a literal file-name character, not an encoded space.
        assertTrue(isLocationInFolder("file:///home/me/A+B/a.flac", "/home/me/A+B"))
        assertFalse(isLocationInFolder("file:///home/me/A+B/a.flac", "/home/me/A B"))
    }

    @Test
    fun safDocumentUrisMatchTheirTree() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        assertTrue(isLocationInFolder("$tree/document/primary%3AMusic%2FRock%2Fa.flac", tree))
        assertFalse(
            isLocationInFolder(
                "content://com.android.externalstorage.documents/tree/primary%3AMusic2/document/primary%3AMusic2%2Fa.flac",
                tree,
            ),
        )
    }

    @Test
    fun blankInputsNeverMatch() {
        assertFalse(isLocationInFolder("file:///home/me/a.flac", "  "))
        assertFalse(isLocationInFolder("", "/home/me"))
    }

    @Test
    fun desktopTracksSitInTheFoldersBelowTheirRoot() {
        assertEquals(listOf("Rock", "70s"), localFolderSegments("file:///home/me/Music/Rock/70s/a.flac", "/home/me/Music"))
        assertEquals(emptyList(), localFolderSegments("file:///home/me/Music/a.flac", "/home/me/Music/"))
        assertEquals(listOf("Café Bar"), localFolderSegments("file:///home/me/Music/Caf%C3%A9%20Bar/a.flac", "/home/me/Music"))
        assertEquals(null, localFolderSegments("file:///elsewhere/a.flac", "/home/me/Music"))
    }

    @Test
    fun desktopSubfolderIdIsAPathThatHoldsOnlyItsOwnTracks() {
        val rock = localSubfolderId("/home/me/Music/", listOf("Rock", "70s"))
        assertEquals("/home/me/Music/Rock/70s", rock)
        assertEquals("/home/me/Music", localSubfolderId("/home/me/Music", emptyList()))
        assertTrue(isLocationInFolder("file:///home/me/Music/Rock/70s/a.flac", rock))
        assertFalse(isLocationInFolder("file:///home/me/Music/Rock/a.flac", rock))
    }

    @Test
    fun safTracksSitInTheFoldersOfTheirDocumentId() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        assertEquals(
            listOf("Rock", "Live 1977"),
            localFolderSegments("$tree/document/primary%3AMusic%2FRock%2FLive%201977%2Fa.flac", tree),
        )
        assertEquals(emptyList(), localFolderSegments("$tree/document/primary%3AMusic%2Fa.flac", tree))
    }

    @Test
    fun safSubfolderIdIsTheDocumentUriAndroidBuildsForThatDirectory() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        val live = localSubfolderId(tree, listOf("Rock", "Live 1977"))
        assertEquals("$tree/document/primary%3AMusic%2FRock%2FLive%201977", live)
        assertTrue(isLocationInFolder("$tree/document/primary%3AMusic%2FRock%2FLive%201977%2Fa.flac", live))
        assertFalse(isLocationInFolder("$tree/document/primary%3AMusic%2FRock%2FLive%201977b%2Fa.flac", live))
        assertFalse(isLocationInFolder("$tree/document/primary%3AMusic%2FRock%2Fa.flac", live))
        // The pinned subfolder still belongs to the configured root, so Home keeps it.
        assertTrue(isLocationInFolder(live, tree))
    }

    @Test
    fun aWholeVolumeTreeHasChildrenWithoutASlash() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3A"
        assertEquals(listOf("Music"), localFolderSegments("$tree/document/primary%3AMusic%2Fa.flac", tree))
        assertEquals("$tree/document/primary%3AMusic", localSubfolderId(tree, listOf("Music")))
    }

    @Test
    fun opaqueSafDocumentIdsKeepFilesInTheRoot() {
        val tree = "content://com.android.providers.downloads.documents/tree/downloads"
        assertEquals(emptyList(), localFolderSegments("$tree/document/msf%3A1234", tree))
    }
}
