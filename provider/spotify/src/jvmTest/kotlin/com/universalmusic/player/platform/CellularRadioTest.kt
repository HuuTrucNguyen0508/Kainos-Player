package com.universalmusic.player.platform

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CellularRadioTest {
    @Test fun namesTheRadioTheStatusBarShows() {
        assertEquals("5G SA", cellularRadioLabel(networkType = 20, overrideType = 0))
        assertEquals("5G NSA", cellularRadioLabel(networkType = 13, overrideType = 3))
        assertEquals("5G+ NSA", cellularRadioLabel(networkType = 13, overrideType = 5))
        assertEquals("4G+", cellularRadioLabel(networkType = 13, overrideType = 1))
        assertEquals("4G", cellularRadioLabel(networkType = 13, overrideType = 0))
        assertEquals("3G", cellularRadioLabel(networkType = 15, overrideType = 0))
        assertEquals("2G", cellularRadioLabel(networkType = 2, overrideType = 0))
        assertEquals("unknown radio", cellularRadioLabel(networkType = 0, overrideType = 0))
    }

    @Test fun addsTheRadioOnlyToMobileData() {
        assertEquals("cellular 5G NSA validated", spotifyNetworkLabel(present = true, cellular = true, validated = true, radio = "5G NSA"))
        assertEquals("vpn+cellular 4G validated", spotifyNetworkLabel(present = true, vpn = true, cellular = true, validated = true, radio = "4G"))
        assertEquals("wifi validated", spotifyNetworkLabel(present = true, wifi = true, validated = true, radio = "4G"))
    }

    @Test fun anAddressTheDeviceHasIsStillOnIt() =
        assertTrue(addressStillOnDevice(InetAddress.getLoopbackAddress()))

    @Test fun anAddressNoInterfaceHasIsGone() =
        // TEST-NET-1 (RFC 5737) is never assigned to a real interface.
        assertFalse(addressStillOnDevice(InetAddress.getByName("192.0.2.1")))
}
