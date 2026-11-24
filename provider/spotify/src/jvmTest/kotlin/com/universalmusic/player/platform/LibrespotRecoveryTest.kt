package com.universalmusic.player.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class LibrespotRecoveryTest {
    private fun recovery(
        wanted: Boolean = true,
        trackId: String? = "a",
        receiverTrack: String? = "a",
        playing: Boolean = false,
        stalledForMs: Long? = null,
        positionMs: Long = 69_000,
        durationMs: Long? = 238_000,
        networkValidated: Boolean = true,
        attempts: Int = 0,
        force: Boolean = false,
    ) = librespotRecovery(wanted, trackId, receiverTrack, playing, stalledForMs, positionMs, durationMs, networkValidated, attempts, force)

    @Test fun reloadsASongTheDroppedConnectionEndedMidway() =
        assertEquals(LibrespotRecovery.Reload(69_000), recovery(playing = false))

    @Test fun reloadsAStallThatOutlastedTheGrace() {
        assertEquals(LibrespotRecovery.Reload(69_000), recovery(playing = true, stalledForMs = LIBRESPOT_STALL_RECOVERY_MS))
        assertEquals(LibrespotRecovery.None, recovery(playing = true, stalledForMs = LIBRESPOT_STALL_RECOVERY_MS - 1))
    }

    @Test fun leavesAPlayingTrackAlone() = assertEquals(LibrespotRecovery.None, recovery(playing = true))

    @Test fun neverResumesWhatKainosPaused() = assertEquals(LibrespotRecovery.None, recovery(wanted = false))

    @Test fun leavesAnotherDevicesTrackAlone() = assertEquals(LibrespotRecovery.None, recovery(receiverTrack = "b"))

    @Test fun reloadsWhenTheReceiverLostItsTrack() =
        assertEquals(LibrespotRecovery.Reload(69_000), recovery(receiverTrack = null))

    @Test fun letsATrackNearItsEndFinish() =
        assertEquals(LibrespotRecovery.None, recovery(positionMs = 238_000 - LIBRESPOT_RECOVERY_END_MARGIN_MS))

    @Test fun waitsForAValidatedNetwork() =
        assertEquals(LibrespotRecovery.WaitForNetwork, recovery(networkValidated = false))

    @Test fun givesUpAfterTheLastAttempt() =
        assertEquals(LibrespotRecovery.GiveUp, recovery(attempts = LIBRESPOT_RECOVERY_ATTEMPTS))

    @Test fun aForcedReplacementReloadsEvenWhilePlaying() =
        assertEquals(LibrespotRecovery.Reload(69_000), recovery(playing = true, force = true))

    @Test fun clockRunsOnlyWhilePlaying() {
        var now = 1_000L
        val clock = LibrespotPositionClock { now }
        clock.anchor(5_000, playing = true)
        now += 64_000
        assertEquals(69_000, clock.positionMs())
        clock.stop()
        now += 30_000
        assertEquals(69_000, clock.positionMs())
        clock.anchor(69_422, playing = false)
        now += 180_000
        assertEquals(69_422, clock.positionMs())
    }
}
