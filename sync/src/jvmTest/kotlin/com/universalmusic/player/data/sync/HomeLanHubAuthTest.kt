package com.universalmusic.player.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeLanHubAuthTest {
    private val self = PairedPeer("kainos-pc", "This PC", CertFingerprint.parse("aa".repeat(32))!!, pairedAtMs = 0L)
    private val phone = PairedPeer("kainos-phone", "Phone", CertFingerprint.parse("bb".repeat(32))!!, pairedAtMs = 1L)

    @Test
    fun noCertificateIsAnonymous() {
        assertEquals(HubCaller.Anonymous, identifyHubCaller(null, self, listOf(phone)))
    }

    @Test
    fun aPairedFingerprintMapsToItsPeerAndTheHubToItself() {
        assertEquals(HubCaller.Paired(phone), identifyHubCaller(phone.fingerprint, self, listOf(phone)))
        assertEquals(HubCaller.Paired(self), identifyHubCaller(self.fingerprint, self, emptyList()))
    }

    @Test
    fun aRemovedPeerIsUnpaired() {
        assertEquals(HubCaller.Unpaired(phone.fingerprint), identifyHubCaller(phone.fingerprint, self, emptyList()))
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
