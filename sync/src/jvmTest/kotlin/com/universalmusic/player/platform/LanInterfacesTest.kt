package com.universalmusic.player.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LanInterfacesTest {
    private fun nic(name: String, vararg ipv4: String, index: Int = 1, up: Boolean = true, loopback: Boolean = false) =
        LanInterface(name, index, up, loopback, ipv4.toList())

    private val laptop = listOf(
        nic("lo", "127.0.0.1", index = 1, loopback = true),
        nic("br-dd56fcd63576", "172.24.0.1", index = 4),
        nic("docker0", "172.17.0.1", index = 5),
        nic("tailscale0", "100.82.114.81", index = 6),
        nic("wlan0", "192.168.1.128", index = 3),
    )

    @Test
    fun wifiWinsOverDockerAndTailscaleWhenItCarriesTheDefaultRoute() {
        assertEquals(LanAddress("wlan0", 3, "192.168.1.128"), selectLanAddress(laptop, defaultRouteInterface = "wlan0"))
    }

    @Test
    fun tailscaleDefaultRouteFallsBackToThePhysicalLanInterface() {
        assertEquals(LanAddress("wlan0", 3, "192.168.1.128"), selectLanAddress(laptop, defaultRouteInterface = "tailscale0"))
    }

    @Test
    fun defaultRouteInterfaceBeatsTheHomeRangePreference() {
        val interfaces = listOf(nic("wlan0", "192.168.0.12", index = 3), nic("eth0", "10.0.0.7", index = 2))
        assertEquals(LanAddress("eth0", 2, "10.0.0.7"), selectLanAddress(interfaces, defaultRouteInterface = "eth0"))
    }

    @Test
    fun withoutADefaultRouteTheHomeRangeIsPreferred() {
        val interfaces = listOf(
            nic("eth1", "172.20.5.4", index = 4),
            nic("eth0", "10.0.0.7", index = 2),
            nic("wlan0", "192.168.0.12", index = 3),
        )
        assertEquals(LanAddress("wlan0", 3, "192.168.0.12"), selectLanAddress(interfaces, defaultRouteInterface = null))
    }

    @Test
    fun downInterfacesAndPublicAddressesAreNeverChosen() {
        val interfaces = listOf(
            nic("wlan0", "192.168.1.128", index = 3, up = false),
            nic("eth0", "8.8.8.8", "172.32.0.1", index = 2),
            nic("virbr0", "192.168.122.1", index = 7),
            nic("veth1", "10.1.1.1", index = 8),
        )
        assertNull(selectLanAddress(interfaces, defaultRouteInterface = "eth0"))
    }
}
