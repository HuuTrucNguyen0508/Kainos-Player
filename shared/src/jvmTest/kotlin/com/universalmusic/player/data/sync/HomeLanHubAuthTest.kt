package com.universalmusic.player.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeLanHubAuthTest {
    private val pairing = HomeLanSyncPairing(
        deviceId = "pc",
        sharedSecretHex = "aabbccddeeff00112233445566778899",
        pairingPin = "123456",
    )

    @Test
    fun acceptsRightTokenAndPin() {
        assertEquals(
            HubAuthResult.OK,
            homeLanHubAuthorize(pairing, "Bearer aabbccddeeff00112233445566778899", "123456"),
        )
    }

    @Test
    fun rejectsWrongOrMissingToken() {
        assertEquals(
            HubAuthResult.BAD_TOKEN,
            homeLanHubAuthorize(pairing, "Bearer aabbccddeeff00112233445566778800", "123456"),
        )
        assertEquals(HubAuthResult.BAD_TOKEN, homeLanHubAuthorize(pairing, "Bearer aabb", "123456"))
        assertEquals(HubAuthResult.BAD_TOKEN, homeLanHubAuthorize(pairing, null, "123456"))
    }

    @Test
    fun rejectsWrongPinAndSkipsPinWhenNotRequired() {
        assertEquals(
            HubAuthResult.BAD_PIN,
            homeLanHubAuthorize(pairing, "Bearer aabbccddeeff00112233445566778899", "654321"),
        )
        assertEquals(
            HubAuthResult.OK,
            homeLanHubAuthorize(pairing.copy(pairingPin = null), "Bearer aabbccddeeff00112233445566778899", null),
        )
    }

    @Test
    fun blobRangeIsBoundedAndValidated() {
        val size = VAULT_BLOB_CHUNK_BYTES * 4L
        assertEquals(0L..(VAULT_BLOB_CHUNK_BYTES - 1L), parseHubBlobRange(null, size))
        assertEquals(0L..(VAULT_BLOB_CHUNK_BYTES - 1L), parseHubBlobRange("bytes=0-", size))
        assertEquals(10L..(VAULT_BLOB_CHUNK_BYTES + 9L), parseHubBlobRange("bytes=10-${size - 1}", size))
        assertEquals(5L..9L, parseHubBlobRange("bytes=5-9", size))
        assertEquals((size - 2)..(size - 1), parseHubBlobRange("bytes=${size - 2}-${size + 50}", size))
        assertNull(parseHubBlobRange("bytes=$size-", size))
        assertNull(parseHubBlobRange("bytes=9-5", size))
        assertNull(parseHubBlobRange("bytes=-5", size))
        assertNull(parseHubBlobRange("bytes=0-1,4-5", size))
        assertNull(parseHubBlobRange("items=0-5", size))
        assertNull(parseHubBlobRange(null, 0L))
    }
}
