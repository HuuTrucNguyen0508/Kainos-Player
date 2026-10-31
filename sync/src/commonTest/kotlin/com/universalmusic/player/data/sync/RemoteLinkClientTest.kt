package com.universalmusic.player.data.sync

import kotlin.time.TimeMark
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteLinkClientTest {
    @Test
    fun aHiddenAppHoldsOnePollAndBecomingVisibleResumesLeaseRefresh() = runTest {
        var visible = false
        val rig = rig(pressable = { visible })
        rig.nextPoll().reply(rig.replyOf("e1").copy(directIntentLease = "lease"))
        advanceTimeBy(REMOTE_REPOST_GAP_MS + 1)
        rig.nextPoll()

        advanceTimeBy(10_000)
        runCurrent()
        assertNull(rig.transport.polls.tryReceive().getOrNull())

        visible = true
        rig.client.reconnectNow()
        val forced = rig.nextPoll()
        assertEquals(-1L, forced.request.knownHubVersion)
        forced.reply(rig.replyOf("e1").copy(directIntentLease = "fresh"))
        runCurrent()
        rig.nextPoll()
        advanceTimeBy(2_001)
        assertEquals(-1L, rig.nextPoll().request.knownHubVersion)
    }

    @Test
    fun aPressHoldingAStaleLeaseGoesOutOnceAndStaysOrdered() = runTest {
        val rig = rig(pressable = { false })
        rig.nextPoll().reply(rig.replyOf("e1").copy(directIntentLease = "lease"))
        advanceTimeBy(REMOTE_REPOST_GAP_MS + 1)
        rig.nextPoll()
        advanceTimeBy(REMOTE_DIRECT_LEASE_FRESH_MS + 1)

        assertIs<RemoteClientSend.Applied>(rig.client.send(RemoteCommand.Play))

        assertEquals(listOf<RemoteCommand>(RemoteCommand.Play), rig.transport.commands)
        assertNull(rig.transport.intents.single().lease)
    }

    @Test
    fun aHubRejectedLeaseIsNotReportedAsApplied() = runTest {
        val rig = rig()
        rig.nextPoll().reply(rig.replyOf("e1").copy(directIntentLease = "lease"))
        runCurrent()
        rig.transport.intentReply = RemoteCommandReply(RemoteStatus(title = "PC song"), leaseRejected = true)

        assertEquals(RemoteClientSend.Unreachable, rig.client.send(RemoteCommand.Play))
    }

    @Test
    fun aSlowForcedRefreshIsNotCancelledByTheNextRefresh() = runTest {
        val rig = rig()
        rig.nextPoll().reply(rig.replyOf("e1").copy(directIntentLease = "lease"))
        advanceTimeBy(REMOTE_REPOST_GAP_MS + 1)
        rig.nextPoll()
        advanceTimeBy(2_001)
        val forced = rig.nextPoll()
        assertEquals(-1L, forced.request.knownHubVersion)

        advanceTimeBy(3_000)
        runCurrent()

        assertNull(rig.transport.polls.tryReceive().getOrNull())
        assertFalse(forced.cancelled.isCompleted)
    }

    @Test
    fun aPressWakesBackoffAndOnlyLatestWaitingIntentIsSent() = runTest {
        var available = false
        val transport = FakeTransport()
        val attempts = mutableListOf<Long>()
        val rig = rig(connect = {
            attempts += testScheduler.currentTime
            if (!available) error("offline")
            transport
        })
        runCurrent()
        advanceTimeBy(250)
        available = true
        val play = async { rig.client.send(RemoteCommand.Play) }
        val pause = async { rig.client.send(RemoteCommand.Pause) }
        runCurrent()
        assertEquals(250L, attempts.last())
        advanceTimeBy(REMOTE_REPOST_GAP_MS)
        runCurrent()
        var poll = transport.polls.tryReceive().getOrThrow()
        while (true) { poll = transport.polls.tryReceive().getOrNull() ?: break }
        poll.reply(rig.replyOf("e1"))
        runCurrent()
        assertEquals(RemoteClientSend.Unreachable, play.await())
        assertIs<RemoteClientSend.Applied>(pause.await())
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause), transport.commands)
    }

    @Test
    fun anOfflineIntentFailsAtFourSecondsAndNeverRunsAfterReconnect() = runTest {
        val rig = rig(connect = { error("offline") })
        runCurrent()
        val result = async { rig.client.send(RemoteCommand.Play) }
        runCurrent()
        advanceTimeBy(4_001)
        assertEquals(RemoteClientSend.Unreachable, result.await())
        assertTrue(rig.transport.commands.isEmpty())
    }

    @Test
    fun eachCommandRunsOnceEvenWhenTheHubOffersItAgain() = runTest {
        val rig = rig()
        rig.reply(epoch = "e1", RemoteEnvelope(1, 0, RemoteCommand.Pause), RemoteEnvelope(2, 0, RemoteCommand.Next))
        val second = rig.nextPoll()
        assertEquals(2, second.request.ackSeq)
        assertEquals("e1", second.request.epoch)

        second.reply(rig.replyOf("e1", RemoteEnvelope(1, 50, RemoteCommand.Pause), RemoteEnvelope(2, 50, RemoteCommand.Next), RemoteEnvelope(3, 0, RemoteCommand.Play)))
        val third = rig.nextPoll()

        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause, RemoteCommand.Next, RemoteCommand.Play), rig.player.applied)
        assertEquals(3, third.request.ackSeq)
    }

    @Test
    fun aLocalChangeAbandonsTheHeldPollAndRepostsWithoutLosingOrRepeatingCommands() = runTest {
        val rig = rig()
        rig.reply(epoch = "e1", RemoteEnvelope(1, 0, RemoteCommand.Pause))
        val held = rig.nextPoll()

        rig.player.state.value = rig.player.state.value!!.copy(status = RemoteStatus(title = "Other song"))
        runCurrent()
        assertTrue(held.cancelled.isCompleted)
        advanceTimeBy(REMOTE_REPOST_GAP_MS)
        val repost = rig.nextPoll()
        assertEquals("Other song", repost.request.status?.title)
        assertEquals(1, repost.request.ackSeq)
        assertTrue(repost.request.generation > held.request.generation)

        repost.reply(rig.replyOf("e1", RemoteEnvelope(1, 10, RemoteCommand.Pause), RemoteEnvelope(2, 0, RemoteCommand.Next)))
        rig.nextPoll()
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause, RemoteCommand.Next), rig.player.applied)
    }

    @Test
    fun stoppingTheLinkMidCommandStillRecordsItAsRun() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        rig.player.gate = gate
        rig.reply(epoch = "e1", RemoteEnvelope(1, 0, RemoteCommand.Pause))
        runCurrent()
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause), rig.player.applied)

        rig.wanted.value = false
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertFalse(rig.client.state.value.active)

        rig.player.gate = null
        rig.wanted.value = true
        val resumed = rig.nextPoll()
        assertEquals(1, resumed.request.ackSeq)
        resumed.reply(rig.replyOf("e1", RemoteEnvelope(1, 100, RemoteCommand.Pause)))
        rig.nextPoll()
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause), rig.player.applied)
    }

    @Test
    fun aCommandPastTheDeadlineAfterTransitIsDroppedAndReported() = runTest {
        val rig = rig()
        val first = rig.nextPoll()
        advanceTimeBy(600)
        first.reply(rig.replyOf("e1", RemoteEnvelope(1, 3_500, RemoteCommand.Play), heldMs = 0))

        val next = rig.nextPoll()
        assertTrue(rig.player.applied.isEmpty())
        assertEquals(1, next.request.ackSeq)
        assertEquals(listOf(1L), next.request.droppedSeqs)

        next.reply(rig.replyOf("e1"))
        assertTrue(rig.nextPoll().request.droppedSeqs.isEmpty())
    }

    @Test
    fun timeTheHubHeldThePollDoesNotCountAsTransit() = runTest {
        val rig = rig()
        val first = rig.nextPoll()
        advanceTimeBy(15_000)
        first.reply(rig.replyOf("e1", RemoteEnvelope(1, 3_000, RemoteCommand.Play), heldMs = 14_800))

        rig.nextPoll()
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Play), rig.player.applied)
    }

    @Test
    fun aFailingHubRetriesOnceQuicklyThenBacksOffToThirtySeconds() = runTest {
        val attempts = mutableListOf<Long>()
        val rig = rig(connect = { attempts += testScheduler.currentTime; error("no route to host") })
        advanceTimeBy(200 + 2_000 + 5_000 + 10_000 + 20_000 + 30_000 + 30_000 + 1)

        assertEquals(listOf(0L, 200, 2_200, 7_200, 17_200, 37_200, 67_200, 97_200), attempts)
        assertTrue(rig.client.state.value.unreachable)
        assertFalse(rig.client.state.value.connected)
    }

    @Test
    fun aFailedPollMarksTheHubUnreachableAtOnceAndAReconnectAsksForListening() = runTest {
        val rig = rig()
        rig.reply(epoch = "e1")
        assertTrue(rig.client.state.value.connected)
        assertEquals(1, rig.connections)

        rig.nextPoll().fail()
        runCurrent()
        assertTrue(rig.client.state.value.unreachable)
        assertFalse(rig.client.state.value.connected)

        advanceTimeBy(2_000)
        rig.reply(epoch = "e1")
        assertTrue(rig.client.state.value.connected)
        assertFalse(rig.client.state.value.unreachable)
        assertEquals(2, rig.connections)
    }

    @Test
    fun aNewHubEpochResetsTheCommandHistory() = runTest {
        val rig = rig()
        rig.reply(epoch = "e1", RemoteEnvelope(1, 0, RemoteCommand.Pause), RemoteEnvelope(2, 0, RemoteCommand.Play))
        rig.nextPoll().reply(rig.replyOf("e2", RemoteEnvelope(1, 0, RemoteCommand.Next)))

        val after = rig.nextPoll()
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause, RemoteCommand.Play, RemoteCommand.Next), rig.player.applied)
        assertEquals("e2", after.request.epoch)
        assertEquals(1, after.request.ackSeq)
    }

    @Test
    fun aSupersededReplyRunsNothingAndKeepsTheHubStatus() = runTest {
        val rig = rig()
        rig.reply(epoch = "e1")
        assertEquals("PC song", rig.client.state.value.hubStatus?.title)

        rig.nextPoll().reply(RemotePollReply("e1", listOf(RemoteEnvelope(1, 0, RemoteCommand.Play)), superseded = true))
        rig.nextPoll()
        assertTrue(rig.player.applied.isEmpty())
        assertEquals("PC song", rig.client.state.value.hubStatus?.title)
    }

    @Test
    fun aTransferCommandPostsTheSnapshotBack() = runTest {
        val rig = rig()
        rig.reply(epoch = "e1", RemoteEnvelope(4, 0, RemoteCommand.PauseForTransfer))
        rig.nextPoll()

        assertEquals(listOf(Triple("e1", 4L, rig.player.transfer)), rig.transport.transfers)
    }

    @Test
    fun commandsToTheHubNeedALiveLink() = runTest {
        val rig = rig()
        assertEquals(RemoteClientSend.Unreachable, rig.client.send(RemoteCommand.Pause))

        rig.reply(epoch = "e1")
        val applied = assertIs<RemoteClientSend.Applied>(rig.client.send(RemoteCommand.Pause))
        assertEquals(false, applied.reply.status?.isPlaying)
        assertEquals(false, rig.client.state.value.hubStatus?.isPlaying)

        rig.transport.commandError = RemoteHubHasNoPlayer()
        assertEquals(RemoteClientSend.NoPlayer, rig.client.send(RemoteCommand.Play))
        assertFalse(rig.client.state.value.hubHasPlayer)

        rig.transport.commandError = IllegalStateException("socket closed")
        assertEquals(RemoteClientSend.Unreachable, rig.client.send(RemoteCommand.Play))
        assertTrue(rig.client.state.value.unreachable)
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause), rig.transport.commands)
    }

    @Test
    fun unreadableLocalArtworkTravelsWithoutAKey() = runTest {
        val rig = rig(artwork = RemoteArtworkReader { null })
        rig.player.state.value = RemotePlayerState(RemoteStatus(title = "A", artworkKey = "abcd1234"), 0, localArtwork = "file:///gone.jpg")

        assertNull(rig.nextPoll().request.status?.artworkKey)
    }

    @Test
    fun readableLocalArtworkIsUploadedOncePerKey() = runTest {
        val rig = rig(artwork = RemoteArtworkReader { byteArrayOf(9, 9) })
        rig.player.state.value = RemotePlayerState(RemoteStatus(title = "A", artworkKey = "abcd1234"), 0, localArtwork = "file:///a.jpg")

        val first = rig.nextPoll()
        assertEquals("abcd1234", first.request.status?.artworkKey)
        first.reply(rig.replyOf("e1"))
        rig.nextPoll().reply(rig.replyOf("e1"))
        rig.nextPoll()
        assertEquals(listOf("abcd1234"), rig.transport.artworkKeys)
    }

    @Test
    fun aSlowArtworkUploadDoesNotHoldUpCommandsOrTheirAcknowledgement() = runTest {
        val rig = rig(artwork = RemoteArtworkReader { byteArrayOf(9, 9) })
        rig.player.state.value = RemotePlayerState(RemoteStatus(title = "A", artworkKey = "abcd1234"), 0, "file:///a.jpg")
        rig.transport.artworkGate = CompletableDeferred()
        rig.reply("e1")
        assertEquals(listOf("abcd1234"), rig.transport.artworkKeys)
        rig.nextPoll().reply(rig.replyOf("e1", RemoteEnvelope(1, 0, RemoteCommand.Pause)))
        assertEquals(1, rig.nextPoll().request.ackSeq)
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause), rig.player.applied)
        assertFalse(rig.transport.artworkGate!!.isCompleted)
        rig.transport.artworkGate!!.complete(Unit)
    }

    @Test
    fun aPollTimeoutBacksOffAndReconnectsWhileStillWanted() = runTest {
        val rig = rig()
        rig.reply(epoch = "e1")
        val held = rig.nextPoll()

        advanceTimeBy(REMOTE_POLL_REQUEST_TIMEOUT_MS)
        runCurrent()
        assertTrue(held.cancelled.isCompleted)
        assertTrue(rig.client.state.value.active)
        assertTrue(rig.client.state.value.unreachable)
        assertFalse(rig.client.state.value.connected)

        advanceTimeBy(2_000)
        rig.reply(epoch = "e1")
        assertEquals(2, rig.connections)
        assertTrue(rig.client.state.value.connected)

        val resumed = rig.nextPoll()
        rig.wanted.value = false
        runCurrent()
        assertTrue(resumed.cancelled.isCompleted)
        advanceTimeBy(REMOTE_POLL_REQUEST_TIMEOUT_MS + 30_000)
        assertFalse(rig.client.state.value.active)
        assertTrue(rig.transport.polls.tryReceive().isFailure)
    }

    @Test
    fun aCommandThatExpiresBehindAnEarlierCommandIsDroppedAndAcknowledged() = runTest {
        val rig = rig()
        val gate = CompletableDeferred<Unit>()
        rig.player.gate = gate
        rig.reply("e1", RemoteEnvelope(1, 0, RemoteCommand.Pause), RemoteEnvelope(2, 0, RemoteCommand.Play))
        advanceTimeBy(6_000)
        gate.complete(Unit)

        val next = rig.nextPoll()
        assertEquals(listOf<RemoteCommand>(RemoteCommand.Pause), rig.player.applied)
        assertEquals(2, next.request.ackSeq)
        assertEquals(listOf(2L), next.request.droppedSeqs)
    }

    @Test
    fun aNewHubEpochUploadsTheSameArtworkAgain() = runTest {
        val rig = rig(artwork = RemoteArtworkReader { byteArrayOf(9, 9) })
        rig.player.state.value = RemotePlayerState(RemoteStatus(title = "A", artworkKey = "abcd1234"), 0, localArtwork = "file:///a.jpg")
        rig.reply("e1")
        rig.nextPoll().reply(rig.replyOf("e2"))
        rig.nextPoll()

        assertEquals(listOf("abcd1234", "abcd1234"), rig.transport.artworkKeys)
    }

    private class PendingPoll(val request: RemotePollRequest) {
        val result = CompletableDeferred<RemotePollReply>()
        val cancelled = CompletableDeferred<Unit>()
        fun reply(reply: RemotePollReply) = result.complete(reply)
        fun fail() = result.completeExceptionally(IllegalStateException("connection reset"))
    }

    private class FakeTransport : RemoteLinkTransport {
        val polls = Channel<PendingPoll>(Channel.UNLIMITED)
        val commands = mutableListOf<RemoteCommand>()
        val intents = mutableListOf<RemoteDirectIntent>()
        var intentReply: RemoteCommandReply? = null
        var commandError: Exception? = null
        val transfers = mutableListOf<Triple<String, Long, RemoteTransferReply>>()
        val artworkKeys = mutableListOf<String>()
        var artworkGate: CompletableDeferred<Unit>? = null

        override suspend fun poll(request: RemotePollRequest): RemotePollReply {
            val pending = PendingPoll(request)
            polls.send(pending)
            try {
                return pending.result.await()
            } catch (cancelled: CancellationException) {
                pending.cancelled.complete(Unit)
                throw cancelled
            }
        }

        override suspend fun command(command: RemoteCommand): RemoteCommandReply {
            commandError?.let { throw it }
            commands += command
            return RemoteCommandReply(RemoteStatus(title = "PC song", isPlaying = false))
        }

        override suspend fun intent(intent: RemoteDirectIntent): RemoteCommandReply {
            intents += intent
            return intentReply ?: command(intent.command)
        }

        override suspend fun postTransfer(epoch: String, seq: Long, reply: RemoteTransferReply) {
            transfers += Triple(epoch, seq, reply)
        }

        override suspend fun putArtwork(key: String, bytes: ByteArray) {
            artworkKeys += key
            artworkGate?.await()
        }

        override suspend fun fetchArtwork(key: String): ByteArray? = null

        override fun close() = Unit
    }

    private class FakePlayer : RemotePlayer {
        override val state = MutableStateFlow<RemotePlayerState?>(RemotePlayerState(RemoteStatus(title = "Phone song"), 0))
        val applied = mutableListOf<RemoteCommand>()
        var gate: CompletableDeferred<Unit>? = null
        val transfer = RemoteTransferReply(failure = RemoteTransferFailure.UNSUPPORTED_TRACK)

        override suspend fun apply(command: RemoteCommand, deadline: TimeMark?): RemoteCommandReply {
            if (deadline?.hasPassedNow() == true) throw RemoteCommandExpired()
            applied += command
            gate?.await()
            return RemoteCommandReply(state.value?.status, transfer.takeIf { command == RemoteCommand.PauseForTransfer })
        }
    }

    private class Rig(
        val scope: TestScope,
        val client: RemoteLinkClient,
        val player: FakePlayer,
        val transport: FakeTransport,
        val wanted: MutableStateFlow<Boolean>,
        private val connectionCount: () -> Int,
    ) {
        val connections get() = connectionCount()

        suspend fun nextPoll(): PendingPoll {
            scope.runCurrent()
            return transport.polls.tryReceive().getOrNull() ?: error("No poll was sent")
        }

        fun replyOf(epoch: String, vararg commands: RemoteEnvelope, heldMs: Long = 0) =
            RemotePollReply(epoch, commands.toList(), RemoteStatus(title = "PC song"), hubVersion = 1, hubName = "PC", heldMs = heldMs, hubHasPlayer = true)

        suspend fun reply(epoch: String, vararg commands: RemoteEnvelope) {
            nextPoll().reply(replyOf(epoch, *commands))
            scope.runCurrent()
        }
    }

    private fun TestScope.rig(
        artwork: RemoteArtworkReader = RemoteArtworkReader { null },
        connect: (suspend (PairedPeer) -> RemoteLinkTransport)? = null,
        pressable: () -> Boolean = { true },
    ): Rig {
        val transport = FakeTransport()
        val player = FakePlayer()
        val wanted = MutableStateFlow(true)
        var connections = 0
        val hub = PairedPeer("pc", "PC", CertFingerprint.parse("cd".repeat(32))!!, pairedAtMs = 1)
        val client = RemoteLinkClient(
            scope = backgroundScope,
            target = { DeliveryTarget(hub, "phone", CertFingerprint.parse("ef".repeat(32))!!) },
            connect = connect ?: { transport },
            player = player,
            artwork = artwork,
            onConnected = { connections++ },
            clock = { testScheduler.currentTime },
            monotonicClock = testScheduler.timeSource,
            pressable = pressable,
        )
        client.run(wanted)
        return Rig(this, client, player, transport, wanted) { connections }
    }
}
