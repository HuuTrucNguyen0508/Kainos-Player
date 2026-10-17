package com.universalmusic.player.data.sync

import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.withoutLegacyHomeLanPairing
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeLanSettingsMigrationTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun oldSecretPairingIsDroppedAndRequiresPairingAgain() {
        val raw = """{"homeLanSyncEnabled":true,"homeLanSyncDeviceId":"phone",
            "homeLanSyncPairing":{"sharedSecretHex":"old-secret","hubHost":"192.0.2.1","pairingPin":"123456"}}"""
        val settings = json.decodeFromString<AppSettings>(raw).withoutLegacyHomeLanPairing()
        assertNull(settings.homeLanSyncHub)
        assertEquals(emptyList(), settings.homeLanSyncPeers)
        assertNull(settings.legacyHomeLanSyncPairing)
        assertEquals(true, settings.homeLanSyncRepairNeeded)
        assertEquals(true, settings.homeLanSyncEnabled)
        assertEquals("phone", settings.homeLanSyncDeviceId)
        val saved = json.encodeToString(AppSettings.serializer(), settings)
        assertEquals(false, saved.contains("sharedSecretHex"))
        assertEquals(false, saved.contains("homeLanSyncPairing"))
        assertEquals(settings, json.decodeFromString<AppSettings>(saved).withoutLegacyHomeLanPairing())
    }

    @Test
    fun certificatePairingSurvivesSettingsRoundTrip() {
        val peer = PairedPeer("pc", "Studio", CertFingerprint.parse("ab".repeat(32))!!, "192.0.2.1", 43823, 25L)
        val settings = AppSettings(homeLanSyncHub = peer)
        val loaded = json.decodeFromString<AppSettings>(json.encodeToString(AppSettings.serializer(), settings))
            .withoutLegacyHomeLanPairing()
        assertEquals(settings, loaded)
        assertEquals(false, loaded.homeLanSyncRepairNeeded)
    }
}
