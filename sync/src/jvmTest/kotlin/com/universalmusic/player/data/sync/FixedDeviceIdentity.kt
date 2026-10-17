package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.DeviceIdentity

/** A device identity whose fingerprint the test picks; [reset] moves to the next one. */
internal class FixedDeviceIdentity(vararg fingerprints: String) : DeviceIdentity {
    private val queue = ArrayDeque(fingerprints.map { CertFingerprint.parse(it)!! })
    private var current = queue.removeFirst()

    override fun fingerprint(): CertFingerprint = current

    override fun reset(): CertFingerprint = queue.removeFirst().also { current = it }
}

internal fun noPairingClient(): (HubEndpoint, CertFingerprint) -> HomeLanPairingClient =
    { _, _ -> error("no pairing client in this test") }
