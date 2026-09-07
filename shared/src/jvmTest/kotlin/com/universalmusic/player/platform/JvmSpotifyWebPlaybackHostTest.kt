package com.universalmusic.player.platform

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JvmSpotifyWebPlaybackHostTest {
    private fun host() = JvmSpotifyWebPlaybackHost(
        tokenSupplier = { error("No token should be needed") },
        locator = object : ChromiumLocator {
            override fun findCandidates(): List<ChromiumInstallation> = emptyList()
        },
    )

    @Test
    fun explicitActivationAllowsTheExistingDeviceToBeUsed() = runTest {
        val host = host()
        host.receiveReady("local-device")
        host.receiveSdkError("autoplay_failed")
        host.receiveActivation()

        assertEquals("local-device", host.ensureDeviceReady()?.deviceId)
    }

    @Test
    fun disconnectedDeviceCannotBeReusedOrActivated() = runTest {
        val host = host()
        host.receiveReady("stale-device")
        host.receiveSdkError("not_ready")
        host.receiveActivation()

        assertNull(host.ensureDeviceReady())
    }

    @Test
    fun autoplayFailureIsNotOverwrittenByARecentHeartbeat() = runTest {
        val host = JvmSpotifyWebPlaybackHost(
            tokenSupplier = { error("No token should be needed") },
            locator = object : ChromiumLocator {
                override fun findCandidates(): List<ChromiumInstallation> = error("Keep the existing activation window")
            },
        )
        host.receiveReady("local-device")
        host.receiveSdkError("autoplay_failed")

        assertNull(host.ensureDeviceReady())
        assertEquals(SpotifyWebPlaybackState.ActivationRequired, host.state.value)
    }
}
