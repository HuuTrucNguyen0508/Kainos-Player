package com.universalmusic.player.ui.screens

import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.ui.components.anyAlbumName
import com.universalmusic.player.ui.components.trackTableColumns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WideLayoutTest {
    @Test fun trackRowsBecomeATableFrom1100dp() {
        assertNull(trackTableColumns(1099.dp, showAlbum = true, trailingWidth = 40.dp))
        val table = assertNotNull(trackTableColumns(1100.dp, showAlbum = true, trailingWidth = 40.dp))
        assertTrue(table.showAlbum)
        assertEquals(1f, table.titleWeight + table.artistWeight + table.albumWeight, .0001f)
        val noAlbum = assertNotNull(trackTableColumns(2000.dp, showAlbum = false, trailingWidth = 40.dp))
        assertEquals(1f, noAlbum.titleWeight + noAlbum.artistWeight, .0001f)
    }

    @Test fun theAlbumColumnShowsOnlyWhenSomeTrackNamesItsAlbum() {
        assertFalse(listOf(track("A", "X", provider = ProviderId.LOCAL)).anyAlbumName())
        assertFalse(listOf(track("A", "X", album = " ", provider = ProviderId.LOCAL)).anyAlbumName())
        assertTrue(
            listOf(track("A", "X", provider = ProviderId.LOCAL), track("B", "X", album = "Gold", provider = ProviderId.LOCAL))
                .anyAlbumName(),
        )
    }

    @Test fun libraryCompactsAt1500AndSplitsAt2000() {
        with(libraryLayout(1499.dp)) {
            assertFalse(compactHeader)
            assertFalse(split)
            assertEquals(1499.dp, songsWidth)
            assertEquals(1499.dp, otherWidth)
        }
        with(libraryLayout(1500.dp)) {
            assertTrue(compactHeader)
            assertFalse(split)
        }
        with(libraryLayout(2890.dp)) {
            assertTrue(split)
            assertEquals((2890.dp - ContentPaneSeamWidth).value, (songsWidth + otherWidth).value, .01f)
            assertEquals(.55f, songsWidth / (songsWidth + otherWidth), .001f)
        }
    }

    @Test fun theRightPaneShowsTheLastOtherTabWhileSongsIsSelected() {
        assertEquals(LibraryTab.Artists, libraryOtherTab(LibraryTab.Artists, LibraryTab.Albums))
        assertEquals(LibraryTab.Folders, libraryOtherTab(LibraryTab.Songs, LibraryTab.Folders))
        assertEquals(LibraryTab.Albums, libraryOtherTab(LibraryTab.Songs, LibraryTab.Songs))
    }

    @Test fun searchSplitsOnlyResultsWithSomethingForTheRightPane() {
        assertTrue(searchSplit(2000.dp, showingResults = true, hasTracks = true, hasPlaylists = true, playlistOpen = false))
        assertTrue(searchSplit(2000.dp, showingResults = true, hasTracks = true, hasPlaylists = false, playlistOpen = true))
        assertFalse(searchSplit(1999.dp, showingResults = true, hasTracks = true, hasPlaylists = true, playlistOpen = false))
        assertFalse(searchSplit(2500.dp, showingResults = true, hasTracks = true, hasPlaylists = false, playlistOpen = false))
        assertFalse(searchSplit(2500.dp, showingResults = true, hasTracks = false, hasPlaylists = true, playlistOpen = false))
        assertFalse(searchSplit(2500.dp, showingResults = false, hasTracks = true, hasPlaylists = true, playlistOpen = true))
    }

    @Test fun settingsBoardColumnsGrowWithWidth() {
        assertEquals(1, settingsBoardColumnCount(1699.dp))
        assertEquals(2, settingsBoardColumnCount(1700.dp))
        assertEquals(3, settingsBoardColumnCount(2300.dp))
        assertEquals(3, settingsBoardColumnCount(2899.dp))
        assertEquals(4, settingsBoardColumnCount(2900.dp))
    }

    @Test fun everyBoardLayoutShowsEachSectionOnce() {
        for (count in 1..4) {
            val placed = settingsBoardColumns(count)
            assertEquals(count, placed.size, "$count columns")
            assertEquals(SettingsSection.entries.toSet(), placed.flatten().toSet())
            assertEquals(SettingsSection.entries.size, placed.flatten().size)
        }
    }

    @Test fun boardColumnsKeepAReadableMeasure() {
        assertEquals(700.dp, settingsBoardColumnWidth(3400.dp, 4))
        assertEquals(((1300 - 40) / 2f).dp, settingsBoardColumnWidth(1300.dp, 2))
    }
}
