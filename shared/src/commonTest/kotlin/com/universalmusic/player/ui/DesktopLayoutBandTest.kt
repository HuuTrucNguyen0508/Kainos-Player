package com.universalmusic.player.ui

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

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
        assertEquals(260.dp, defaultNowPlayingBandHeight(800.dp))
        assertEquals(1120.5.dp, clampNowPlayingBandHeight(2000.dp, 2490.dp))
        assertEquals(DesktopNowPlayingBandMinHeight, clampNowPlayingBandHeight(10.dp, 2490.dp))
    }
}
