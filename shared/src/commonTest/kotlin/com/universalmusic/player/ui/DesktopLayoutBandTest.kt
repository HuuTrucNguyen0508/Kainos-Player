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
}
