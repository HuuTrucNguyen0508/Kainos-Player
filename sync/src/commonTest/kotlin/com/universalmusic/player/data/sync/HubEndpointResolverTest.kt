package com.universalmusic.player.data.sync

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HubEndpointResolverTest {
    private val pin = "ab".repeat(32)
    private val otherPin = "cd".repeat(32)
    private val stored = HubEndpoint("192.168.1.20", 43822)
    private val moved = HubEndpoint("192.168.1.57", 43822)

    private class FakeProber(private val answers: Map<HubEndpoint, Result<Unit>>, private val delayMs: Long = 50) {
        val probed = mutableListOf<HubEndpoint>()
        suspend fun probe(endpoint: HubEndpoint): Result<Unit> {
            probed += endpoint
            delay(delayMs)
            return answers[endpoint] ?: Result.failure(IllegalStateException("Connection refused"))
        }
    }

    private fun browser(vararg hubs: DiscoveredHub, delayMs: Long = 200) = HubBrowser {
        flow {
            for (hub in hubs) {
                delay(delayMs)
                emit(hub)
            }
            awaitCancellation()
        }
    }

    private fun resolver(browser: HubBrowser, prober: FakeProber, logs: MutableList<String> = mutableListOf()) =
        HubEndpointResolver(browser, prober::probe, log = { logs += it })

    @Test
    fun storedAddressThatAnswersSkipsDiscovery() = runTest {
        val prober = FakeProber(mapOf(stored to Result.success(Unit)))
        val logs = mutableListOf<String>()
        val result = resolver(browser(DiscoveredHub(pin, "pc", moved.host, moved.port)), prober, logs)
            .resolve(stored, pin)

        assertEquals(ResolveResult.Resolved(stored, ResolvedVia.STORED), result)
        assertEquals(listOf(stored), prober.probed)
        assertEquals(emptyList(), logs)
    }

    @Test
    fun staleStoredAddressFallsBackToThePinnedHubFoundByMdns() = runTest {
        val prober = FakeProber(mapOf(moved to Result.success(Unit)))
        val result = resolver(browser(DiscoveredHub(pin.uppercase(), "pc", moved.host, moved.port)), prober)
            .resolve(stored, pin)

        assertEquals(ResolveResult.Resolved(moved, ResolvedVia.MDNS), result)
        assertEquals(listOf(stored, moved), prober.probed)
    }

    @Test
    fun hubWithADifferentFingerprintIsNeverProbed() = runTest {
        val prober = FakeProber(mapOf(moved to Result.success(Unit)))
        val result = resolver(browser(DiscoveredHub(otherPin, "stranger", moved.host, moved.port)), prober)
            .resolve(stored, pin)

        assertEquals(
            ResolveResult.HubNotFound(
                "Hub not found: stored 192.168.1.20:43822: Connection refused; " +
                    "mDNS found no hub with the pinned certificate within 4 s",
            ),
            result,
        )
        assertEquals(listOf(stored), prober.probed)
    }

    @Test
    fun rightFingerprintThatFailsThePinnedProbeIsRejected() = runTest {
        val impostor = HubEndpoint("192.168.1.66", 44099)
        val prober = FakeProber(
            mapOf(impostor to Result.failure(IllegalStateException("Hub certificate pin mismatch"))),
        )
        val result = resolver(browser(DiscoveredHub(pin, "pc", impostor.host, impostor.port)), prober)
            .resolve(stored, pin)

        assertEquals(
            ResolveResult.HubNotFound(
                "Hub not found: stored 192.168.1.20:43822: Connection refused; " +
                    "mDNS 192.168.1.66:44099: Hub certificate pin mismatch; " +
                    "1 mDNS hub(s) advertised the pinned certificate but failed the pinned TLS probe",
            ),
            result,
        )
    }

    @Test
    fun silentNetworkGivesUpAfterTheProbeAndBrowseBudgets() = runTest {
        val prober = FakeProber(mapOf(stored to Result.success(Unit)), delayMs = 60_000)
        val result = resolver(browser(), prober).resolve(stored, pin)

        assertEquals(
            ResolveResult.HubNotFound(
                "Hub not found: stored 192.168.1.20:43822: timed out after 3 s; " +
                    "mDNS found no hub with the pinned certificate within 4 s",
            ),
            result,
        )
        assertEquals(7_000, currentTime)
    }

    @Test
    fun storedAddressIsNotProbedTwiceWhenMdnsAdvertisesIt() = runTest {
        val prober = FakeProber(mapOf(moved to Result.success(Unit)))
        val result = resolver(
            browser(DiscoveredHub(pin, "pc", stored.host, stored.port), DiscoveredHub(pin, "pc", moved.host, moved.port)),
            prober,
        ).resolve(stored, pin)

        assertEquals(ResolveResult.Resolved(moved, ResolvedVia.MDNS), result)
        assertEquals(listOf(stored, moved), prober.probed)
    }

    @Test
    fun noStoredAddressGoesStraightToDiscovery() = runTest {
        val prober = FakeProber(mapOf(moved to Result.success(Unit)))
        val result = resolver(browser(DiscoveredHub(pin, "pc", moved.host, moved.port)), prober).resolve(null, pin)

        assertEquals(ResolveResult.Resolved(moved, ResolvedVia.MDNS), result)
        assertEquals(listOf(moved), prober.probed)
    }

    @Test
    fun brokenDiscoveryEndsTheSearchWithoutWaitingForTheBrowseBudget() = runTest {
        val prober = FakeProber(emptyMap())
        val failing = HubBrowser { flow { throw IllegalStateException("org.freedesktop.Avahi was not provided") } }
        val logs = mutableListOf<String>()
        val result = resolver(failing, prober, logs).resolve(stored, pin)

        assertEquals(
            ResolveResult.HubNotFound(
                "Hub not found: stored 192.168.1.20:43822: Connection refused; " +
                    "mDNS found no hub with the pinned certificate within 4 s",
            ),
            result,
        )
        assertEquals("discovery failed: org.freedesktop.Avahi was not provided", logs.last())
        assertEquals(50, currentTime)
    }
}
