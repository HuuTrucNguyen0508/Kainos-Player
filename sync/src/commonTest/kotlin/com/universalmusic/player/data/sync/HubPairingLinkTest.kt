package com.universalmusic.player.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class HubPairingLinkTest {
    private val fp = "ab".repeat(32)
    private val hub = DiscoveredHub(fp, "pc-7f3a", "192.168.1.20", 43822, "Kainos on studio (é)")

    @Test
    fun aPcLinkRoundTripsToTheSameHub() {
        val link = hubPairingLink(hub)
        assertEquals(
            "kainos-pair:3?fp=$fp&id=pc-7f3a&host=192.168.1.20&port=43822&name=Kainos%20on%20studio%20%28%C3%A9%29",
            link,
        )
        assertEquals(hub, parseHubPairingLink(link).getOrThrow())
    }

    @Test
    fun anUppercaseFingerprintIsPinnedInLowercase() {
        val link = hubPairingLink(hub).replace(fp, fp.uppercase())
        assertEquals(fp, parseHubPairingLink(link).getOrThrow().fp)
    }

    @Test
    fun theOldSharedSecretLinkIsRefusedWithWhatToDo() {
        assertEquals(
            "This is an old pairing link. Open Devices and sync on your PC and scan the code shown there.",
            failure("kainos-homesync:2?host=192.168.1.20&port=43822&cert=$fp"),
        )
        assertEquals(
            "This is an old pairing link. Open Devices and sync on your PC and scan the code shown there.",
            failure("kainos-homesync:1?host=192.168.1.20&port=43822"),
        )
    }

    @Test
    fun anotherProtocolVersionIsRefused() {
        assertEquals(
            "This code is from a different version of Kainos. Update the app on both devices.",
            failure(hubPairingLink(hub).replace("kainos-pair:3?", "kainos-pair:4?")),
        )
    }

    @Test
    fun aMalformedPinIsRefused() {
        val notAKainosCode = "This is not a Kainos pairing code."
        assertEquals(notAKainosCode, failure(hubPairingLink(hub).replace(fp, fp.dropLast(1))))
        assertEquals(notAKainosCode, failure(hubPairingLink(hub).replace(fp, "zz".repeat(32))))
        assertEquals(notAKainosCode, failure(hubPairingLink(hub).replace("fp=$fp&", "")))
        assertEquals(notAKainosCode, failure(hubPairingLink(hub) + "&fp=$fp"))
    }

    @Test
    fun aHostOutsideTheHomeNetworkIsRefused() {
        val outside = "This code does not point to a PC on your home network."
        for (host in listOf("8.8.8.8", "evil.example", "192.168.1.256", "192.168.01.20", "100.101.102.103", "")) {
            assertEquals(outside, failure(hubPairingLink(hub.copy(host = host))), host)
        }
    }

    @Test
    fun otherTextIsNotAPairingCode() {
        val notAKainosCode = "This is not a Kainos pairing code."
        for (raw in listOf("https://example.com", "", "kainos-pair:3", hubPairingLink(hub.copy(port = 0)),
            hubPairingLink(hub.copy(name = "")), hubPairingLink(hub) + "%zz", hubPairingLink(hub) + "&x")) {
            assertEquals(notAKainosCode, failure(raw), raw)
        }
        assertEquals(notAKainosCode, failure(hubPairingLink(hub.copy(name = "n".repeat(65)))))
    }

    private fun failure(raw: String): String? = parseHubPairingLink(raw).exceptionOrNull()?.message
}
