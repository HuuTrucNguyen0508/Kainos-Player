package com.universalmusic.player.data.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteLinkHostTest {
    private val phone = PairedPeer("phone", "Poco", CertFingerprint.parse("ab".repeat(32))!!, pairedAtMs = 1)

    @Test
    fun theFirstPollIsAnsweredAtOnceWithTheEpochAndHubStatus() = runTest {
        val host = host()
        host.updateHubStatus(RemoteStatus(title = "Hub song", isPlaying = true), hasPlayer = true)

        val reply = host.pollNow(request(generation = 1))

        assertEquals("e1", reply.epoch)
        assertEquals(0, reply.heldMs)
        assertEquals("Hub song", reply.hubStatus?.title)
        assertTrue(reply.hubHasPlayer)
        assertFalse(reply.superseded)
    }

    @Test
    fun aPollWithNothingToSayIsHeldAndASendWakesIt() = runTest {
        val host = host()
        val first = host.pollNow(request(generation = 1))
        val held = async { host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = first.hubVersion)) }
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(held.isCompleted)
        assertTrue(host.peer("phone").connected)

        val sent = async { host.send("phone", RemoteCommand.Pause) }
        runCurrent()
        val reply = held.await()

        assertEquals(listOf(RemoteEnvelope(1, 0, RemoteCommand.Pause)), reply.commands)
        assertEquals(1_000, reply.heldMs)
        assertFalse(sent.isCompleted)
        host.pollNow(request(generation = 3, epoch = first.epoch, ackSeq = 1))
        assertEquals(RemoteSendOutcome.DELIVERED, sent.await())
    }

    @Test
    fun aHeldPollTimesOutEmptyAfterTheHold() = runTest {
        val host = host()
        val first = host.pollNow(request(generation = 1))
        val held = async { host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = first.hubVersion)) }
        advanceTimeBy(REMOTE_POLL_HOLD_MS + 1)

        val reply = held.await()
        assertEquals(REMOTE_POLL_HOLD_MS, reply.heldMs)
        assertTrue(reply.commands.isEmpty())
        assertFalse(reply.superseded)
    }

    @Test
    fun aMaterialHubChangeWakesTheHeldPollButAPositionTickDoesNot() = runTest {
        val host = host()
        host.updateHubStatus(RemoteStatus(title = "A", isPlaying = true, positionMs = 0), hasPlayer = true)
        val first = host.pollNow(request(generation = 1))
        val held = async { host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = first.hubVersion)) }
        advanceTimeBy(1_000)
        host.updateHubStatus(RemoteStatus(title = "A", isPlaying = true, positionMs = 1_000), hasPlayer = true)
        runCurrent()
        assertFalse(held.isCompleted)

        host.updateHubStatus(RemoteStatus(title = "A", isPlaying = false, positionMs = 1_000), hasPlayer = true)
        val reply = held.await()
        assertEquals(false, reply.hubStatus?.isPlaying)
        assertTrue(reply.hubVersion > first.hubVersion)
    }

    @Test
    fun anAcknowledgedCommandIsNeverOfferedAgain() = runTest {
        val host = host()
        val first = host.pollNow(request(generation = 1))
        val sent = async(start = CoroutineStart.UNDISPATCHED) { host.send("phone", RemoteCommand.Next) }
        val delivered = host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = first.hubVersion))
        assertEquals(listOf(1L), delivered.commands.map { it.seq })

        val after = async { host.pollNow(request(generation = 3, epoch = first.epoch, ackSeq = 1, knownHubVersion = first.hubVersion)) }
        runCurrent()
        assertEquals(RemoteSendOutcome.DELIVERED, sent.await())
        advanceTimeBy(REMOTE_POLL_HOLD_MS + 1)
        assertTrue(after.await().commands.isEmpty())
    }

    @Test
    fun aCommandNobodyFetchesExpiresUndeliveredAndIsNeverDeliveredLater() = runTest {
        val host = host()
        val first = host.pollNow(request(generation = 1))
        val sent = async { host.send("phone", RemoteCommand.Play) }
        advanceTimeBy(REMOTE_COMMAND_DEADLINE_MS + REMOTE_ACK_GRACE_MS + 1)

        assertEquals(RemoteSendOutcome.NOT_DELIVERED, sent.await())
        val late = host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = -5))
        assertTrue(late.commands.isEmpty())
    }

    @Test
    fun aFetchedButUnacknowledgedCommandIsUnconfirmedAndNotOfferedAfterTheDeadline() = runTest {
        val host = host()
        val first = host.pollNow(request(generation = 1))
        val sent = async(start = CoroutineStart.UNDISPATCHED) { host.send("phone", RemoteCommand.Play) }
        assertEquals(1, host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = -5)).commands.size)
        advanceTimeBy(REMOTE_COMMAND_DEADLINE_MS)

        val again = host.pollNow(request(generation = 3, epoch = first.epoch, knownHubVersion = -5))
        assertTrue(again.commands.isEmpty())
        advanceTimeBy(REMOTE_ACK_GRACE_MS + 1)
        assertEquals(RemoteSendOutcome.UNCONFIRMED, sent.await())
    }

    @Test
    fun aCommandTheDeviceDroppedAsLateIsReportedUndelivered() = runTest {
        val host = host()
        val first = host.pollNow(request(generation = 1))
        val sent = async(start = CoroutineStart.UNDISPATCHED) { host.send("phone", RemoteCommand.Play) }
        host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = -5))

        host.pollNow(request(generation = 3, epoch = first.epoch, ackSeq = 1, droppedSeqs = listOf(1), knownHubVersion = -5))
        assertEquals(RemoteSendOutcome.NOT_DELIVERED, sent.await())
    }

    @Test
    fun aDeviceThatIsNotPollingIsRefusedAtOnce() = runTest {
        val host = host()
        assertEquals(RemoteSendOutcome.UNREACHABLE, host.send("phone", RemoteCommand.Play))

        host.pollNow(request(generation = 1))
        advanceTimeBy(REMOTE_CONNECTED_GRACE_MS + 1)
        assertFalse(host.peer("phone").connected)
        assertEquals(RemoteSendOutcome.UNREACHABLE, host.send("phone", RemoteCommand.Play))
        assertEquals(REMOTE_CONNECTED_GRACE_MS + 1, testScheduler.currentTime)
    }

    @Test
    fun aNewerPollSupersedesTheHeldOneAndALateOlderPollChangesNothing() = runTest {
        val host = host()
        val first = host.pollNow(request(generation = 1))
        val older = async { host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = first.hubVersion, title = "old")) }
        runCurrent()
        val newer = async { host.pollNow(request(generation = 3, epoch = first.epoch, knownHubVersion = first.hubVersion, title = "new")) }
        runCurrent()
        assertTrue(older.await().superseded)

        val late = host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = first.hubVersion, title = "late"))
        assertTrue(late.superseded)
        assertEquals("new", host.peer("phone").status?.title)

        val sent = async { host.send("phone", RemoteCommand.Next) }
        runCurrent()
        assertEquals(listOf(RemoteCommand.Next), newer.await().commands.map { it.command })
        sent.cancel()
    }

    @Test
    fun aPollNamingAnotherDeviceIsForbidden() = runTest {
        val host = host()
        val refused = host.poll(phone, request(generation = 1).copy(deviceId = "someone-else"))

        assertEquals(RemoteHostReply.Refused(403, "The poll names another device"), refused)
        assertNull(host.peer("someone-else").status)
    }

    @Test
    fun aNewEpochIgnoresOldAcknowledgementsAndRestartsSequences() = runTest {
        var epochs = 0
        val host = RemoteLinkHost(hubName = { "PC" }, clock = { testScheduler.currentTime }, newEpoch = { "e${++epochs}" })
        host.start()
        val first = host.pollNow(request(generation = 1))
        async(start = CoroutineStart.UNDISPATCHED) { host.send("phone", RemoteCommand.Play) }
        host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = -5))

        host.start()
        val reply = host.pollNow(request(generation = 3, epoch = "e1", ackSeq = 7, knownHubVersion = first.hubVersion))
        assertEquals("e2", reply.epoch)
        assertTrue(reply.commands.isEmpty())
        val sent = async(start = CoroutineStart.UNDISPATCHED) { host.send("phone", RemoteCommand.Pause) }
        assertEquals(listOf(1L), host.pollNow(request(generation = 4, epoch = "e2", ackSeq = 0, knownHubVersion = -5)).commands.map { it.seq })
        host.pollNow(request(generation = 5, epoch = "e2", ackSeq = 1, knownHubVersion = -5))
        assertEquals(RemoteSendOutcome.DELIVERED, sent.await())
    }

    @Test
    fun aRestartedDeviceIsNeverOfferedWhatItsOldProcessFetched() = runTest {
        val host = host()
        val first = host.pollNow(request(generation = 1))
        val sent = async(start = CoroutineStart.UNDISPATCHED) { host.send("phone", RemoteCommand.Next) }
        assertEquals(1, host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = -5)).commands.size)

        val restarted = host.pollNow(request(generation = 1, linkId = "second-process", knownHubVersion = -5))
        assertFalse(restarted.superseded)
        assertTrue(restarted.commands.isEmpty())
        assertEquals(RemoteSendOutcome.UNCONFIRMED, sent.await())
    }

    @Test
    fun stoppingWakesEveryHeldPollAndEndsPendingCommands() = runTest {
        val host = host()
        val first = host.pollNow(request(generation = 1))
        val held = async { host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = first.hubVersion)) }
        runCurrent()
        val sent = async { host.send("phone", RemoteCommand.Play) }
        runCurrent()
        val sentAgain = async(start = CoroutineStart.UNDISPATCHED) { host.send("phone", RemoteCommand.Pause) }

        host.stop()
        runCurrent()

        assertTrue(held.isCompleted)
        assertEquals(RemoteSendOutcome.STOPPED, sentAgain.await())
        assertTrue(sent.isCompleted)
        assertEquals(0, testScheduler.currentTime)
        assertEquals(RemoteHostReply.Refused(503, "The hub is stopping"), host.poll(phone, request(generation = 9)))
    }

    @Test
    fun aTransferRequestReceivesTheSnapshotTheDevicePostsBack() = runTest {
        val host = host()
        val first = host.pollNow(request(generation = 1))
        val transfer = async(start = CoroutineStart.UNDISPATCHED) { host.requestTransfer("phone") }
        val delivered = host.pollNow(request(generation = 2, epoch = first.epoch, knownHubVersion = -5))
        val envelope = delivered.commands.single()
        assertEquals(RemoteCommand.PauseForTransfer, envelope.command)

        val snapshot = RemoteTransferReply(failure = RemoteTransferFailure.UNSUPPORTED_TRACK)
        assertEquals(RemoteHostReply.Refused(409, "The hub restarted"), host.receiveTransfer(phone, "stale", envelope.seq, snapshot))
        assertEquals(RemoteHostReply.Ok(Unit), host.receiveTransfer(phone, first.epoch, envelope.seq, snapshot))
        assertEquals(RemoteHostTransfer.Received(snapshot), transfer.await())
    }

    @Test
    fun aTransferThatNeverArrivesFails() = runTest {
        val host = host()
        host.pollNow(request(generation = 1))
        val transfer = async { host.requestTransfer("phone") }
        advanceTimeBy(REMOTE_TRANSFER_WAIT_MS + 1)

        assertEquals(RemoteHostTransfer.Failed(RemoteSendOutcome.NOT_DELIVERED), transfer.await())
    }

    @Test
    fun aHubWithoutAPlayerShowsNoStatus() = runTest {
        val host = host()
        host.updateHubStatus(RemoteStatus(title = "hidden"), hasPlayer = false)

        val reply = host.pollNow(request(generation = 1))
        assertNull(reply.hubStatus)
        assertFalse(reply.hubHasPlayer)
    }

    @Test
    fun onlyTheLatestArtworkPerDeviceIsKept() = runTest {
        val host = host()
        host.pollNow(request(generation = 1))
        host.receiveArtwork(phone, "k1", byteArrayOf(1))
        host.receiveArtwork(phone, "k2", byteArrayOf(2))

        assertNull(host.peerArtwork("phone", "k1"))
        assertEquals(listOf<Byte>(2), host.peerArtwork("phone", "k2")?.toList())
        assertIs<RemoteHostReply.Refused>(host.receiveArtwork(phone, "big", ByteArray(REMOTE_ARTWORK_MAX_BYTES + 1)))
    }

    private suspend fun TestScope.host() =
        RemoteLinkHost(hubName = { "PC" }, clock = { testScheduler.currentTime }, newEpoch = { "e1" }).also { it.start() }

    private suspend fun RemoteLinkHost.pollNow(request: RemotePollRequest): RemotePollReply =
        (poll(phone, request) as RemoteHostReply.Ok).value

    private fun request(
        generation: Long,
        epoch: String? = null,
        ackSeq: Long = 0,
        knownHubVersion: Long = -1,
        droppedSeqs: List<Long> = emptyList(),
        title: String = "Phone song",
        linkId: String = "link-1",
    ) = RemotePollRequest(
        deviceId = "phone",
        linkId = linkId,
        generation = generation,
        epoch = epoch,
        ackSeq = ackSeq,
        knownHubVersion = knownHubVersion,
        status = RemoteStatus(title = title),
        droppedSeqs = droppedSeqs,
    )
}
