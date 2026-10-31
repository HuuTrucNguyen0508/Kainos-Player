package com.universalmusic.player.data.sync

import kotlin.test.*
import kotlin.time.TestTimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest

@OptIn(kotlin.time.ExperimentalTime::class)
class RemoteDirectIntentReceiverTest {
    private class Player : RemotePlayer {
        override val state = MutableStateFlow(RemotePlayerState(RemoteStatus(), 0))
        val commands = mutableListOf<RemoteCommand>()
        override suspend fun apply(command: RemoteCommand, deadline: TimeMark?): RemoteCommandReply {
            if (deadline?.hasPassedNow() == true) throw RemoteCommandExpired()
            commands += command
            state.value = state.value.copy(status = state.value.status.copy(isPlaying = command == RemoteCommand.Play))
            return RemoteCommandReply(state.value.status)
        }
    }
    @Test fun duplicateAndReorderedIntentsCannotUndoPause() = runTest {
        val receiver = RemoteDirectIntentReceiver()
        val lease = receiver.lease("phone")
        val player = Player()
        val play = RemoteDirectIntent(RemoteCommand.Play, "sender", 1, 0, lease)
        val pause = play.copy(command = RemoteCommand.Pause, seq = 2)
        receiver.apply("phone", pause, player)
        assertFalse(receiver.apply("phone", play, player).leaseRejected)
        assertFalse(receiver.apply("phone", pause, player).leaseRejected)
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause), player.commands)
        assertFalse(player.state.value.status.isPlaying)
    }
    @Test fun aDelayedLeasedPlayCannotUndoANewerPauseSentWithoutALease() = runTest {
        val receiver = RemoteDirectIntentReceiver()
        val lease = receiver.lease("phone")
        val player = Player()
        receiver.apply("phone", RemoteDirectIntent(RemoteCommand.Pause, "sender", 2, 0), player)
        receiver.apply("phone", RemoteDirectIntent(RemoteCommand.Play, "sender", 1, 0, lease), player)
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause), player.commands)
    }
    @Test fun receiverLeaseExpiresEvenWhenPacketAgeLiesAboutTransit() = runTest {
        val time = TestTimeSource()
        val receiver = RemoteDirectIntentReceiver(time)
        val lease = receiver.lease("phone")
        val player = Player()
        time += 4_001.milliseconds
        val reply = receiver.apply("phone", RemoteDirectIntent(RemoteCommand.Play, "sender", 1, 0, lease), player)
        assertTrue(player.commands.isEmpty())
        assertTrue(reply.leaseRejected)
    }
    @Test fun wrongPeerExpiredAgeAndToggleAreRejected() = runTest {
        val receiver = RemoteDirectIntentReceiver()
        val lease = receiver.lease("phone")
        val player = Player()
        val play = RemoteDirectIntent(RemoteCommand.Play, "sender", 1, 0, lease)
        receiver.apply("stranger", play, player)
        receiver.apply("phone", play.copy(ageMs = 4_000), player)
        receiver.apply("phone", play.copy(command = RemoteCommand.TogglePlay), player)
        assertTrue(player.commands.isEmpty())
        receiver.apply("phone", play, player)
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Play), player.commands)
    }
}
