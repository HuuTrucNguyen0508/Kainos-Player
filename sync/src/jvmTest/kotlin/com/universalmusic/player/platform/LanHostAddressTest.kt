package com.universalmusic.player.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanHostAddressTest {
    @Test
    fun skipsDockerBridgesListedBeforeWifi() {
        val candidates = listOf(
            "br-dd56fcd63576" to "172.24.0.1",
            "docker0" to "172.17.0.1",
            "tailscale0" to "100.82.114.81",
            "wlan0" to "192.168.1.128",
        )
        assertEquals("192.168.1.128", pickLanHostAddress(candidates))
    }

    @Test
    fun prefersHomeRangeOverOtherPrivateRanges() {
        val candidates = listOf("eth1" to "172.20.5.4", "eth0" to "10.0.0.7", "wlan0" to "192.168.0.12")
        assertEquals("192.168.0.12", pickLanHostAddress(candidates))
    }

    @Test
    fun returnsNullWhenOnlyVirtualOrPublicAddressesExist() {
        val candidates = listOf("virbr0" to "192.168.122.1", "veth1" to "10.1.1.1", "eth0" to "8.8.8.8")
        assertNull(pickLanHostAddress(candidates))
    }
}
