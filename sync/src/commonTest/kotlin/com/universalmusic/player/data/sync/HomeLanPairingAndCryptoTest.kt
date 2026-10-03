package com.universalmusic.player.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HomeLanPairingAndCryptoTest {
    @Test
    fun pairingUriRoundTrip() {
        val cert = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val pairing = HomeLanSyncPairing(
            deviceId = "kainos-pc",
            sharedSecretHex = "aabbccddeeff00112233445566778899",
            hubHost = "192.168.1.10",
            hubPort = 43822,
            pairingPin = "123456",
            hubCertSha256Hex = cert,
            encryptPayloads = true,
        )
        val uri = pairing.toPairingUri()
        assertTrue(uri.startsWith(HOME_LAN_PAIRING_URI_PREFIX))
        assertTrue(uri.contains("cert=$cert"))
        val parsed = parseHomeLanPairingUri(uri)
        assertNotNull(parsed)
        assertEquals("192.168.1.10", parsed.hubHost)
        assertEquals(43822, parsed.hubPort)
        assertEquals(pairing.sharedSecretHex, parsed.sharedSecretHex)
        assertEquals("kainos-pc", parsed.peerDeviceId)
        assertEquals("123456", parsed.pairingPin)
        assertEquals(cert, parsed.hubCertSha256Hex)
    }

    @Test
    fun pairingUriWithoutCertIsRejected() {
        val v1 = "kainos-homesync:1?host=192.168.1.10&port=43822&secret=aabb&deviceId=pc&pin=123456&enc=1"
        assertEquals(null, parseHomeLanPairingUri(v1))
    }

    @Test
    fun aesGcmRoundTrip() {
        val key = SyncPayloadCrypto.keyFromSharedSecret("deadbeef")
        val plain = "vault-index-json".encodeToByteArray()
        val sealed = SyncPayloadCrypto.encrypt(plain, key)
        val opened = SyncPayloadCrypto.decrypt(sealed, key)
        assertEquals("vault-index-json", opened.decodeToString())
    }
}
