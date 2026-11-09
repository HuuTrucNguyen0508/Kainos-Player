package com.universalmusic.player.ui.screens

import androidx.compose.ui.unit.dp
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.ui.components.namesAlbums
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
        // Everything fits the row: insets, cover, text, marks and trailing.
        val used = 32 + 40 + 150 + 84 + 40 + 12 * 7 + (table.titleWidth + table.artistWidth + table.albumWidth!!).value
        assertTrue(used <= 1100.5f, "row needs $used dp")
    }

    @Test fun textColumnsStopGrowingSoAWideListDoesNotStretchItsTitles() {
        val wide = assertNotNull(trackTableColumns(2850.dp, showAlbum = false, trailingWidth = 40.dp))
        assertEquals(640.dp, wide.titleWidth)
        assertEquals(400.dp, wide.artistWidth)
        assertNull(wide.albumWidth)
        val pane = assertNotNull(trackTableColumns(1580.dp, showAlbum = true, trailingWidth = 180.dp))
        assertTrue(pane.titleWidth < 640.dp && pane.titleWidth > pane.artistWidth)
    }

    @Test fun theAlbumColumnShowsWhenAQuarterOfTheTracksNameTheirAlbum() {
        fun list(named: Int, unnamed: Int) =
            List(named) { track("N$it", "X", album = "Gold", provider = ProviderId.LOCAL) } +
                List(unnamed) { track("U$it", "X", provider = ProviderId.LOCAL) }
        assertFalse(emptyList<com.universalmusic.player.domain.model.Track>().namesAlbums())
        assertFalse(listOf(track("A", "X", album = " ", provider = ProviderId.LOCAL)).namesAlbums())
        assertFalse(list(named = 2, unnamed = 625).namesAlbums())
        assertTrue(list(named = 1, unnamed = 3).namesAlbums())
        // YouTube's placeholder album is the service's name, not an album.
        assertFalse(List(4) { track("Y$it", "X", album = "YouTube Music", provider = ProviderId.YOUTUBE_MUSIC) }.namesAlbums())
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
        assertEquals(3, settingsBoardColumnCount(2799.dp))
        assertEquals(4, settingsBoardColumnCount(2800.dp))
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
