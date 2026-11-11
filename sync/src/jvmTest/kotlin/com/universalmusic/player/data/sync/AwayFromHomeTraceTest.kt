package com.universalmusic.player.data.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.PrintStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Away from home every delivery retry, probe and TLS setup wrote its own lines: 44-82% of the
 * phone's 2 MB trace on 2026-11-03..11, pushing playback evidence out of shared logs.
 */
class AwayFromHomeTraceTest {
    private val hubPeer = PairedPeer("kainos-pc", "PC", CertFingerprint.parse("aa".repeat(32))!!, "127.0.0.1", 1, 1L)

    private fun traced(block: () -> Unit): List<String> {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        try { block() } finally { System.setOut(original) }
        return captured.toString().lines().filter { it.startsWith("TRACE ") }
    }

    @Test fun repeatedDeliveryFailuresWriteOneLineUntilTheHubAnswers() {
        var hubHome = false
        val delivery = ListeningDelivery(
            store = PendingStore(items("a", "b")),
            target = { DeliveryTarget(hubPeer, "kainos-phone", CertFingerprint.parse("bb".repeat(32))!!) },
            connect = { if (hubHome) FakeHub() else throw IOException("Hub not found: stored 192.168.1.128:43822: timed out after 3 s") },
            clock = { 1_000 },
        )
        val away = traced { runBlocking { repeat(6) { delivery.deliverPending() } } }
        assertEquals(
            listOf("listening delivery failed: Hub not found: stored 192.168.1.128:43822: timed out after 3 s"),
            away.map { it.substringAfter("[HomeSync] ") },
        )
        hubHome = true
        val back = traced { runBlocking { delivery.deliverPending() } }
        assertEquals(
            "previous failure repeated 5 more times: Hub not found: stored 192.168.1.128:43822: timed out after 3 s",
            back.first().substringAfter("[HomeSync] "),
        )
    }
}
