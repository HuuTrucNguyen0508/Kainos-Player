package com.universalmusic.player.platform

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultRouteTest {
    private val header = "Iface\tDestination\tGateway\tFlags\tRefCnt\tUse\tMetric\tMask\tMTU\tWindow\tIRTT"

    @Test fun wifiDefaultRouteCountsAsOnline() {
        assertTrue(hasDefaultRoute("$header\nwlan0\t00000000\t0101A8C0\t0003\t0\t0\t600\t00000000\t0\t0\t0\n"))
    }

    @Test fun dockerAndTailscaleRoutesAloneAreOffline() {
        val virtualOnly = "$header\ndocker0\t000011AC\t00000000\t0001\t0\t0\t0\t0000FFFF\t0\t0\t0\n" +
            "tailscale0\t00407364\t00000000\t0001\t0\t0\t0\t00FFFFFF\t0\t0\t0\n"
        assertFalse(hasDefaultRoute(virtualOnly))
    }
}
