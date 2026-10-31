package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.HomeLanHubConfig
import com.universalmusic.player.platform.JvmDeviceIdentity
import com.universalmusic.player.platform.pinnedHomeLanHttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.url
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeMark
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/** Same source runs on b3a3d80 and head. HTTP faults apply below the real client, including retries. */
class RemoteDirectFaultHarnessTest {
    @Test fun measureFinalState() = runBlocking {
        val output = System.getenv("KAINOS_REMOTE_DIRECT_OUTPUT") ?: return@runBlocking
        val baseline = System.getenv("KAINOS_REMOTE_DIRECT_BASELINE") == "1"
        val file = File(output)
        file.writeText("arm\tscenario\trun\tcorrect\tfinal_ms\tapplications\n")
        for (scenario in listOf("stale-display", "double-delivery", "ambiguous-reply", "reordered-request", "mixed-version")) repeat(10) { run ->
            val dir = Files.createTempDirectory("remote-direct-").toFile()
            val pc = JvmDeviceIdentity(dir.resolve("pc")).material()
            val phone = JvmDeviceIdentity(dir.resolve("phone")).material()
            val peer = PairedPeer("phone", "Phone", phone.fingerprint, pairedAtMs = 1)
            val player = RecordingPlayer()
            val host = RemoteLinkHost({ "PC" })
            host.updateHubStatus(player.state.value!!.status, true)
            val port = ServerSocket(0).use { it.localPort }
            val hub = DesktopHomeLanSyncHub(HomeLanHubConfig(deviceId = "pc", port = port, peers = { listOf(peer) },
                pairing = PairingHost({ pc.fingerprint }, { "pc" }, onPaired = {}), onHearts = { it }, onPlaylists = { it },
                vault = null, heartedVaultFileNames = { null }, remote = host, guiPlayer = { player }), pc, bindHost = "127.0.0.1")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val http = pinnedHomeLanHttpClient(pc.fingerprint, phone.keyManagers())
            var calls = 0
            var fault = true
            http.plugin(HttpSend).intercept { request ->
                val direct = request.url.toString().endsWith(HOME_LAN_SYNC_PATH_V3_REMOTE_COMMAND)
                if (direct) calls++
                if (direct && calls == 1 && scenario == "reordered-request") delay(200)
                val reply = execute(request)
                if (direct && fault) {
                    if (scenario == "double-delivery") { execute(request); fault = false }
                    if (scenario == "ambiguous-reply" || scenario == "mixed-version") {
                        fault = false
                        error("injected ambiguous reply after application")
                    }
                }
                reply
            }
            val original = HttpRemoteLinkTransport(HubEndpoint("127.0.0.1", port), http)
            // On head, removing the optional field emulates an old hub's reply without changing routes.
            val wire = object : RemoteLinkTransport by original {
                override suspend fun poll(request: RemotePollRequest): RemotePollReply {
                    val reply = original.poll(request)
                    if (scenario != "mixed-version") return reply
                    val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                    val tree = json.parseToJsonElement(json.encodeToString(RemotePollReply.serializer(), reply)) as kotlinx.serialization.json.JsonObject
                    return json.decodeFromString(RemotePollReply.serializer(), kotlinx.serialization.json.JsonObject(tree.filterKeys { it != "directIntentLease" }).toString())
                }
            }
            try {
                hub.start()
                val client = RemoteLinkClient(scope, { DeliveryTarget(PairedPeer("pc", "PC", pc.fingerprint, pairedAtMs = 1), "phone", phone.fingerprint) }, { wire }, player)
                client.run(MutableStateFlow(true))
                withTimeout(5_000) { client.state.first { it.connected } }
                val started = System.nanoTime()
                val wanted = scenario != "stale-display"
                // Stale display says playing while receiver is paused. New UI sends Pause.
                val first = if (baseline && scenario != "reordered-request") RemoteCommand.TogglePlay else if (wanted) RemoteCommand.Play else RemoteCommand.Pause
                val firstSend = scope.async { client.send(first) }
                if (scenario != "reordered-request") firstSend.await()
                // Burst ends paused. Reply ambiguity is on the first press; newer Pause must win.
                if (scenario != "stale-display") {
                    delay(30)
                    client.send(if (baseline && scenario != "reordered-request") RemoteCommand.TogglePlay else RemoteCommand.Pause)
                }
                firstSend.await()
                delay(250)
                val expected = false
                val correct = player.state.value!!.status.isPlaying == expected
                val elapsed = player.applied.lastOrNull()?.second?.let { (it - started) / 1_000_000.0 } ?: 0.0
                file.appendText("${if (baseline) "baseline" else "head"}\t$scenario\t$run\t$correct\t$elapsed\t${player.applied.size}\n")
                assertTrue(player.applied.all { (it.second - started) / 1_000_000 < REMOTE_COMMAND_DEADLINE_MS }, "original deadline violated")
                if (!baseline) {
                    assertTrue(correct, "last intent lost: $scenario")
                    assertTrue(player.applied.size <= 2, "duplicate application: $scenario")
                    if (scenario == "mixed-version") assertTrue(calls <= 2, "retried against old hub")
                }
            } finally { scope.cancel(); original.close(); hub.stop(); dir.deleteRecursively() }
        }
    }
    private class RecordingPlayer : RemotePlayer {
        override val state = MutableStateFlow<RemotePlayerState?>(RemotePlayerState(RemoteStatus(title = "Fixture"), 0))
        val applied = CopyOnWriteArrayList<Pair<RemoteCommand, Long>>()
        override suspend fun apply(command: RemoteCommand, deadline: TimeMark?): RemoteCommandReply {
            if (deadline?.hasPassedNow() == true) throw RemoteCommandExpired()
            applied += command to System.nanoTime()
            val playing = when (command) { RemoteCommand.Play -> true; RemoteCommand.Pause -> false; else -> !state.value!!.status.isPlaying }
            state.value = RemotePlayerState(state.value!!.status.copy(isPlaying = playing), System.currentTimeMillis())
            return RemoteCommandReply(state.value!!.status)
        }
    }
}
