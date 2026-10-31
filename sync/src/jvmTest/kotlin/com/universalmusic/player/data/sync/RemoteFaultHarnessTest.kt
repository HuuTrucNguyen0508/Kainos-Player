package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.JvmDeviceIdentity
import com.universalmusic.player.platform.pinnedHomeLanHttpClient
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeMark
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Opt in with scripts/perf/remote-faults. Real host, client, routes and pinned mutual TLS.
 * Faults sit outside HTTP: a dropped reply still reached the real server. No fake acknowledgements.
 */
class RemoteFaultHarnessTest {
    @Test
    fun measure() = runBlocking {
        val output = System.getenv("KAINOS_REMOTE_PERF_OUTPUT") ?: return@runBlocking
        val scenarios = (System.getenv("KAINOS_REMOTE_SCENARIOS") ?: "held,reply-flight,gap,repost,double,five,loss2-request,loss2-reply,loss10-request,loss10-reply,latency200,latency1000,blip2000,blip10000,restart,suspend60000,artwork,position,direct").split(',')
        val file = File(output)
        file.writeText("scenario\trun\tpress\tapplied_ms\toutcome\tapplications\n")
        for (scenario in scenarios) repeat(if (scenario.startsWith("loss2-")) 50 else 10) { run ->
            val dir = Files.createTempDirectory("remote-fault-").toFile()
            val pc = JvmDeviceIdentity(dir.resolve("pc")).material()
            val phone = JvmDeviceIdentity(dir.resolve("phone")).material()
            val port = ServerSocket(0).use { it.localPort }
            val peer = PairedPeer("phone", "Phone", phone.fingerprint, pairedAtMs = 1)
            val host = RemoteLinkHost({ "PC" })
            val player = ProbePlayer()
            val hub = DesktopHomeLanSyncHub(HomeLanHubConfig(
                deviceId = "pc", port = port, peers = { listOf(peer) },
                pairing = PairingHost({ pc.fingerprint }, { "pc" }, onPaired = {}),
                onHearts = { it }, onPlaylists = { it }, vault = null,
                heartedVaultFileNames = { null }, remote = host, guiPlayer = { player },
            ), pc, bindHost = "127.0.0.1")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val entered = CompletableDeferred<Unit>()
            var fault = false
            var polls = 0
            var injected = false
            var suspendedMs = 0L
            val receiverClock = object : TimeSource {
                override fun markNow(): TimeMark {
                    val start = System.nanoTime()
                    val offset = suspendedMs
                    return object : TimeMark {
                        override fun elapsedNow() = (System.nanoTime() - start).nanoseconds + (suspendedMs - offset).milliseconds
                    }
                }
            }
            val original = HttpRemoteLinkTransport(HubEndpoint("127.0.0.1", port), pinnedHomeLanHttpClient(pc.fingerprint, phone.keyManagers()))
            val wire = object : RemoteLinkTransport by original {
                override fun close() {} // reconnects reuse the pinned pool in this fault fixture
                override suspend fun poll(request: RemotePollRequest): RemotePollReply {
                    polls++
                    if (fault) {
                        entered.complete(Unit)
                        if (!injected && scenario.startsWith("blip")) {
                            injected = true
                            delay(scenario.removePrefix("blip").toLong()); error("injected Wi-Fi outage")
                        }
                        if (scenario.startsWith("latency")) delay(scenario.removePrefix("latency").toLong() / 2)
                        // Deterministic loss location advances between runs, covering the first request too.
                        val divisor = if (scenario.startsWith("loss2-")) 50 else 10
                        if (scenario.endsWith("-request") && (polls + run) % divisor == 0) error("injected request loss")
                    }
                    val reply = original.poll(request)
                    if (fault) {
                        if (scenario == "reply-flight" || scenario == "gap") { entered.complete(Unit); delay(100) }
                        if (scenario.startsWith("latency")) delay(scenario.removePrefix("latency").toLong() / 2)
                        val divisor = if (scenario.startsWith("loss2-")) 50 else 10
                        if (scenario.endsWith("-reply") && (polls + run) % divisor == 0) error("injected reply loss")
                    }
                    if (scenario == "suspend60000" && reply.commands.isNotEmpty() && !injected) {
                        // Simulate the receiver resuming with a reply buffered for 60s. Only its
                        // monotonic clock advances; no assumption about the sender's wall clock.
                        injected = true
                        suspendedMs += 60_000
                    }
                    return if (scenario == "duplicate") reply.copy(commands = reply.commands + reply.commands) else reply
                }
                override suspend fun putArtwork(key: String, bytes: ByteArray) {
                    entered.complete(Unit)
                    delay(1_000)
                    original.putArtwork(key, bytes)
                }
            }
            try {
                hub.start()
                host.updateHubStatus(RemoteStatus(title = "PC"), true)
                val client = RemoteLinkClient(scope, { DeliveryTarget(PairedPeer("pc", "PC", pc.fingerprint, pairedAtMs = 1), "phone", phone.fingerprint) },
                    { wire }, player, artwork = RemoteArtworkReader { byteArrayOf(1, 2, 3) }, monotonicClock = receiverClock)
                val wanted = MutableStateFlow(true)
                client.run(wanted)
                withTimeout(5_000) { client.state.first { it.connected } }
                fault = true
                when (scenario) {
                    "artwork" -> {
                        player.state.value = RemotePlayerState(RemoteStatus(title = "Phone", artworkKey = "abc123"), System.currentTimeMillis(), "fixture")
                        withTimeout(5_000) { entered.await() }
                    }
                    "repost" -> { player.state.value = RemotePlayerState(RemoteStatus(title = "new"), System.currentTimeMillis()); delay(30) }
                    "position" -> player.state.value = player.state.value!!.copy(status = player.state.value!!.status.copy(positionMs = 10))
                    "held", "double", "five", "direct" -> delay(50)
                    else -> {
                        host.updateHubStatus(RemoteStatus(title = "wake"), true)
                        if (scenario == "reply-flight" || scenario == "gap") withTimeout(5_000) { entered.await() }
                        else delay(50)
                    }
                }
                val count = when (scenario) { "double" -> 2; "five" -> 5; else -> 1 }
                val starts = CopyOnWriteArrayList<Long>()
                coroutineScope {
                    repeat(count) { press ->
                        launch {
                            val command = if (press % 2 == 0) RemoteCommand.Play else RemoteCommand.Pause
                            val start = System.nanoTime()
                            starts += start
                            val result = async { if (scenario == "direct") client.send(command).toString() else host.send("phone", command).name }
                            if (scenario == "restart") { delay(5); host.stop(); host.start() }
                            val outcome = result.await()
                            val applications = player.applications.getOrNull(press)?.takeIf { it.first == command && it.second >= start }?.let { listOf(it) } ?: emptyList()
                            val elapsed = applications.firstOrNull()?.let { (it.second - start) / 1_000_000.0 }
                            synchronized(file) { file.appendText("$scenario\t$run\t$press\t${elapsed ?: "NA"}\t$outcome\t${applications.size}\n") }
                            assertTrue(applications.size <= 1, "duplicate application: $scenario")
                            assertTrue(elapsed == null || elapsed < REMOTE_COMMAND_DEADLINE_MS, "late command: $scenario $elapsed")
                        }
                        delay(if (scenario == "five") 200 else 20)
                    }
                }
                // Keep observing past recovery: a timed-out Play must never arrive late.
                if (scenario == "blip10000") delay(10_100)
                assertTrue(player.applications.size <= count, "late duplicate after recovery")
                player.applications.forEachIndexed { index, (_, appliedAt) ->
                    assertTrue((appliedAt - starts[index]) / 1_000_000 < REMOTE_COMMAND_DEADLINE_MS, "late application after recovery")
                }
            } finally {
                scope.cancel()
                original.close()
                hub.stop()
                dir.deleteRecursively()
            }
        }
    }

    private class ProbePlayer : RemotePlayer {
        override val state = MutableStateFlow<RemotePlayerState?>(RemotePlayerState(RemoteStatus(title = "Phone"), System.currentTimeMillis()))
        val applications = CopyOnWriteArrayList<Pair<RemoteCommand, Long>>()
        override suspend fun apply(command: RemoteCommand, deadline: TimeMark?): RemoteCommandReply {
            if (deadline?.hasPassedNow() == true) throw RemoteCommandExpired()
            applications += command to System.nanoTime()
            // Deliberately leave state unchanged: measures transport application, with no status repost hiding it.
            return RemoteCommandReply(state.value?.status)
        }
    }
}
