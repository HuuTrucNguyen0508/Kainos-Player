package com.universalmusic.player.platform

import com.universalmusic.player.data.sync.CertFingerprint
import io.ktor.client.HttpClient

/** This device's home-sync key pair and self-signed certificate. */
interface DeviceIdentity {
    /** Creates the key on first use. */
    fun fingerprint(): CertFingerprint

    /**
     * Deletes the key and creates a new one; every device that trusted the old one must pair again.
     * [DeviceIdentityResetException.preservedFingerprint] proves safe recovery after a failed reset.
     */
    fun reset(): CertFingerprint
}

class DeviceIdentityResetException(
    val preservedFingerprint: CertFingerprint?,
    cause: Throwable,
) : Exception("Could not reset this device's identity", cause)

expect fun platformDeviceIdentity(): DeviceIdentity

/**
 * HTTPS client that presents this device's certificate and only trusts a hub leaf certificate
 * whose fingerprint is [pin]. There is no hostname check, so any address that reaches the pinned
 * hub works.
 */
expect fun createPinnedHomeLanHttpClient(pin: CertFingerprint): HttpClient

const val HUB_PIN_MISMATCH = "Hub certificate pin mismatch"
