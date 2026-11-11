package com.universalmusic.player.data.sync

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals

/** `kainos-hub.service` restarted 98 times on 2026-11-10 because these cases exited 1. */
class HubOnlyExitStatusTest {
    @Test fun aPortHeldByAnotherProcessIsNotAFailureToRetry() {
        ServerSocket().use { holder ->
            holder.reuseAddress = true
            holder.bind(InetSocketAddress(0))
            assertEquals(0, hubOnlyExitStatus(ownerLockAcquired = true, port = holder.localPort))
        }
    }

    @Test fun anotherInstanceHoldingTheOwnerLockIsNotAFailure() {
        assertEquals(0, hubOnlyExitStatus(ownerLockAcquired = false, port = freePort()))
    }

    @Test fun aHubThatCouldNotListenOnAFreePortIsAFailure() {
        assertEquals(1, hubOnlyExitStatus(ownerLockAcquired = true, port = freePort()))
    }

    private fun freePort() = ServerSocket(0).use { it.localPort }
}
