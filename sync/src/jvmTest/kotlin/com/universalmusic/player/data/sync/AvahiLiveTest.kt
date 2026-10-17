package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.currentLanAddress
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFalse

class AvahiLiveTest {
    @Test
    fun advertisedHubIsBrowsableOnTheLanAddressAndGoneAfterStop() = runBlocking {
        assumeTrue(System.getProperty("kainos.avahi") == "true")
        val lan = assertNotNull(currentLanAddress(), "no eligible LAN interface on this machine")
        val fp = Random.nextBytes(32).toHex()
        val port = 45_000 + Random.nextInt(1_000)
        val advertiser = AvahiHubAdvertiser(port, fp, deviceId = "avahi-live", instanceLabel = "live-$port")
        val browser = AvahiHubBrowser()
        suspend fun browseOurs(timeoutMs: Long) =
            withTimeoutOrNull(timeoutMs) { browser.browse().filter { it.fp == fp }.firstOrNull() }

        advertiser.start()
        val found = try {
            browseOurs(10_000)
        } finally {
            advertiser.stop()
        }
        println("AVAHI HS2 found=$found lan=$lan")
        assertEquals(DiscoveredHub(fp, "avahi-live", lan.ipv4, port), found)

        val stoppedAt = System.currentTimeMillis()
        var stillListed = true
        while (stillListed && System.currentTimeMillis() - stoppedAt < 10_000) {
            stillListed = browseOurs(2_000) != null
        }
        println("AVAHI HS2 stillListed=$stillListed ${System.currentTimeMillis() - stoppedAt} ms after stop")
        assertFalse(stillListed, "record still browsable 10 s after stop")
    }
}
