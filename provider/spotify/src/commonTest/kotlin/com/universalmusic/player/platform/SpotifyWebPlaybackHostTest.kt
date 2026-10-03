package com.universalmusic.player.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpotifyWebPlaybackHostTest {
    @Test
    fun describeLibrespotConnectionFailureMapsAccessPointRefused() {
        val detail =
            "failed to connect to ap-gew1.spotify.com/104.199.65.9 (port 4070) from /:: (port 50958): connect failed: ECONNREFUSED"
        val message = describeLibrespotConnectionFailure(detail)
        assertNotNull(message)
        assertTrue(message.contains("4070"))
        assertTrue(message.contains("VPN"))
    }

    @Test
    fun describeLibrespotConnectionFailureIgnoresUnrelatedErrors() {
        assertNull(describeLibrespotConnectionFailure("Spotify rejected the librespot credentials."))
    }

    @Test
    fun isRetryableLibrespotConnectFailureMatchesRefusedAndTimeout() {
        assertTrue(isRetryableLibrespotConnectFailure(IllegalStateException("connect failed: ECONNREFUSED")))
        assertTrue(isRetryableLibrespotConnectFailure(IllegalStateException("SocketTimeoutException")))
        assertFalse(isRetryableLibrespotConnectFailure(IllegalStateException("Premium account required")))
    }
}
