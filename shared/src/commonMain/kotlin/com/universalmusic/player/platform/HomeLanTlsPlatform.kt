package com.universalmusic.player.platform

import com.universalmusic.player.data.sync.HomeLanSyncPairing
import io.ktor.client.HttpClient

/**
 * HTTPS client that only trusts a hub leaf certificate whose SHA-256 (DER) matches [certPinHex].
 * Hostname checks rely on SAN entries baked into the hub cert (LAN IP at pairing time).
 */
expect fun createPinnedHomeLanHttpClient(certPinHex: String): HttpClient

/**
 * Desktop: generate a new self-signed hub cert, persist PKCS12, return SHA-256 pin.
 * Android: returns null (hub is desktop-only).
 */
expect fun generateHomeLanHubCertPin(pairing: HomeLanSyncPairing): String?
