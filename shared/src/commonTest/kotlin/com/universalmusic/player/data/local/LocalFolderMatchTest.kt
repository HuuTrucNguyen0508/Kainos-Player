package com.universalmusic.player.data.local

import kotlin.test.Test
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
}
