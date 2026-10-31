package com.universalmusic.player.data.sync

import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.session.SessionSnapshot
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.platform.JvmDeviceIdentity
import java.io.File
import kotlin.test.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Seeds only fresh, explicitly named perf profiles. The real GUI processes own playback. */
class RemoteDesktopFixtureTest {
    @Test
    fun prepare() {
        val root = System.getenv("KAINOS_REMOTE_DESKTOP_SETUP")?.let(::File) ?: return
        require(!root.exists()) { "Refuse to overwrite a profile" }
        val pc = root.resolve("pc").apply { mkdirs() }
        val phone = root.resolve("phone").apply { mkdirs() }
        val pcKey = JvmDeviceIdentity(pc.resolve("home-lan")).material()
        val phoneKey = JvmDeviceIdentity(phone.resolve("home-lan")).material()
        val hubPort = System.getenv("KAINOS_REMOTE_DESKTOP_PORT").toInt()
        val hub = PairedPeer("remote-proof-pc", "Proof PC", pcKey.fingerprint, "127.0.0.1", hubPort, 1)
        val client = PairedPeer("remote-proof-phone", "Proof phone", phoneKey.fingerprint, pairedAtMs = 1)
        val snapshot = SessionSnapshot(items = listOf(PersistedQueueItem("proof-entry", PersistedTrack(
            canonicalId = "local:remote-proof-tone", title = "Remote proof tone", durationMs = 120_000,
            sources = listOf(PersistedSource("LOCAL", "remote-proof-tone", root.resolve("tone.wav").absolutePath)),
        ))))
        for (profile in listOf(pc, phone)) profile.resolve("playback-session.json").writeText(Json.encodeToString(snapshot))
        val common = AppSettings(homeLanSyncEnabled = true, localMusicFoldersConfigured = true)
        pc.resolve("settings.json").writeText(Json.encodeToString(common.copy(homeLanSyncRole = HomeLanSyncRole.HUB,
            homeLanSyncDeviceId = hub.deviceId, homeLanSyncPeers = listOf(client))))
        phone.resolve("settings.json").writeText(Json.encodeToString(common.copy(homeLanSyncRole = HomeLanSyncRole.CLIENT,
            homeLanSyncDeviceId = client.deviceId, homeLanSyncHub = hub)))
    }
}
