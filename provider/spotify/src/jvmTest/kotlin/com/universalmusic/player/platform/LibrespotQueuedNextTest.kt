package com.universalmusic.player.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibrespotQueuedNextTest {
    @Test fun queuesTheWantedTrackOnAnEmptyQueue() =
        assertEquals(LibrespotQueueEdit(remove = null, add = "b"), planLibrespotQueue(queued = null, wanted = "b", current = "a"))

    @Test fun replacesAnOutdatedQueuedTrack() =
        assertEquals(LibrespotQueueEdit(remove = "b", add = "c"), planLibrespotQueue(queued = "b", wanted = "c", current = "a"))

    @Test fun clearsTheQueuedTrackWhenNothingIsWanted() =
        assertEquals(LibrespotQueueEdit(remove = "b", add = null), planLibrespotQueue(queued = "b", wanted = null, current = "a"))

    @Test fun leavesAQueueThatAlreadyMatchesAlone() {
        assertNull(planLibrespotQueue(queued = "b", wanted = "b", current = "a"))
        assertNull(planLibrespotQueue(queued = null, wanted = null, current = "a"))
    }

    @Test fun neverQueuesThePlayingTrack() {
        assertNull(planLibrespotQueue(queued = null, wanted = "a", current = "a"))
        assertEquals(LibrespotQueueEdit(remove = "b", add = null), planLibrespotQueue(queued = "b", wanted = "a", current = "a"))
    }

    private fun step(
        trackId: String = "b",
        current: String? = "a",
        queued: String? = "b",
        advancedTo: String? = null,
        receiverNext: String? = "b",
        playing: Boolean = true,
        positionMs: Long = 60_000,
        durationMs: Long? = 200_000,
        waited: Boolean = false,
    ) = librespotAdoptStep(trackId, current, queued, advancedTo, receiverNext, playing, positionMs, durationMs, waited)

    @Test fun takesATrackLibrespotAlreadyAdvancedInto() {
        assertEquals(LibrespotAdoptStep.TAKE_CURRENT, step(current = "b", queued = null, advancedTo = "b", receiverNext = null))
        // librespot's own track-change event has not arrived yet.
        assertEquals(LibrespotAdoptStep.TAKE_CURRENT, step(current = "b", receiverNext = null))
    }

    @Test fun waitsForAnAdvanceThatIsMomentsAway() {
        assertEquals(LibrespotAdoptStep.WAIT_FOR_ADVANCE, step(positionMs = 197_500))
        assertEquals(LibrespotAdoptStep.SKIP_TO_QUEUED, step(positionMs = 197_500, waited = true))
    }

    @Test fun skipsToTheQueuedTrackOnAManualSkipOrAPausedEnd() {
        assertEquals(LibrespotAdoptStep.SKIP_TO_QUEUED, step())
        assertEquals(LibrespotAdoptStep.SKIP_TO_QUEUED, step(positionMs = 199_000, playing = false))
        assertEquals(LibrespotAdoptStep.SKIP_TO_QUEUED, step(positionMs = 199_000, durationMs = null))
    }

    @Test fun loadsATrackThatIsNotQueuedHere() {
        assertEquals(LibrespotAdoptStep.LOAD, step(queued = "c", receiverNext = "c"))
        // The receiver dropped its queue (another device, a reset): our record is stale.
        assertEquals(LibrespotAdoptStep.LOAD, step(receiverNext = null))
    }

    @Test fun replayingTheCurrentTrackLoadsItFromTheStart() =
        assertEquals(LibrespotAdoptStep.LOAD, step(trackId = "a", current = "a", queued = "b"))

    @Test fun labelsTheDefaultNetwork() {
        assertEquals("none", spotifyNetworkLabel(present = false))
        assertEquals("cellular validated", spotifyNetworkLabel(present = true, cellular = true, validated = true))
        assertEquals("wifi unvalidated", spotifyNetworkLabel(present = true, wifi = true))
        assertEquals("vpn+cellular validated", spotifyNetworkLabel(present = true, vpn = true, cellular = true, validated = true))
        assertEquals("other unvalidated", spotifyNetworkLabel(present = true))
    }
}
