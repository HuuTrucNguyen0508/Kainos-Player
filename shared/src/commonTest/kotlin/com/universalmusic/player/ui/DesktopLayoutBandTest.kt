package com.universalmusic.player.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import com.universalmusic.player.data.settings.NowPlayingDock
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopLayoutBandTest {
    @Test
    fun narrowBelow840() {
        assertEquals(DesktopLayoutBand.Narrow, desktopLayoutBand(839.dp))
        assertEquals(DesktopLayoutBand.Narrow, desktopLayoutBand(600.dp))
    }

    @Test
    fun standardFrom840UntilWide() {
        assertEquals(DesktopLayoutBand.Standard, desktopLayoutBand(840.dp))
        assertEquals(DesktopLayoutBand.Standard, desktopLayoutBand(1199.dp))
    }

    @Test
    fun wideFrom1200() {
        assertEquals(DesktopLayoutBand.Wide, desktopLayoutBand(1200.dp))
        assertEquals(DesktopLayoutBand.Wide, desktopLayoutBand(1920.dp))
    }

    @Test
    fun portraitMonitorDocksThePlayerOnTop() {
        // A tiled window on the rotated 1440x2560 Asus, a half-height split of it, and the ultrawide.
        assertEquals(true, desktopPortrait(1420.dp, 2490.dp))
        assertEquals(false, desktopPortrait(1420.dp, 1235.dp))
        assertEquals(false, desktopPortrait(3420.dp, 1400.dp))
        // A phone-width window stays on the narrow layout however tall it is.
        assertEquals(false, desktopPortrait(600.dp, 2000.dp))
    }

    @Test
    fun portraitBandKeepsMostOfTheHeightForTheLibrary() {
        assertEquals(400.dp, defaultNowPlayingBandHeight(2490.dp))
        assertEquals(300.dp, defaultNowPlayingBandHeight(800.dp))
        assertEquals(1120.5.dp, clampNowPlayingBandHeight(2000.dp, 2490.dp))
        assertEquals(DesktopNowPlayingBandMinHeight, clampNowPlayingBandHeight(10.dp, 2490.dp))
    }

    @Test
    fun automaticDockKeepsTheOldPlaces() {
        assertEquals(DockEdge.RIGHT, resolveDockEdge(NowPlayingDock.AUTO, portrait = false))
        assertEquals(DockEdge.BOTTOM, resolveDockEdge(NowPlayingDock.AUTO, portrait = true))
    }

    @Test
    fun aChosenDockHoldsInEveryWindowShape() {
        for (edge in DockEdge.entries) {
            assertEquals(edge, resolveDockEdge(edge.setting, portrait = false))
            assertEquals(edge, resolveDockEdge(edge.setting, portrait = true))
        }
    }

    @Test
    fun aDropLandsOnTheNearestEdgeAsAShareOfTheWindow() {
        val w = 1920f
        val h = 1080f
        assertEquals(DockEdge.LEFT, dockEdgeAt(40f, 540f, w, h))
        assertEquals(DockEdge.RIGHT, dockEdgeAt(1880f, 540f, w, h))
        assertEquals(DockEdge.TOP, dockEdgeAt(960f, 30f, w, h))
        assertEquals(DockEdge.BOTTOM, dockEdgeAt(960f, 1060f, w, h))
        // 200px from the left is 10% of the width; 150px from the top is 14% of the height.
        assertEquals(DockEdge.LEFT, dockEdgeAt(200f, 150f, w, h))
        // 400px from the left is 21%; 150px from the top is still 14%, so top wins.
        assertEquals(DockEdge.TOP, dockEdgeAt(400f, 150f, w, h))
    }

    @Test
    fun theMiddleOfTheWindowCancels() {
        assertNull(dockEdgeAt(960f, 540f, 1920f, 1080f))
        assertNull(dockEdgeAt(700f, 400f, 1920f, 1080f))
        assertNull(dockEdgeAt(10f, 10f, 0f, 0f))
    }
}
