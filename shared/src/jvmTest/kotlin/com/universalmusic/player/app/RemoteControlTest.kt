package com.universalmusic.player.app

import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.data.session.PersistedQueueItem
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.sync.DeliveryTarget
import com.universalmusic.player.data.sync.CertFingerprint
import com.universalmusic.player.data.sync.HomeLanSyncRole
import com.universalmusic.player.data.sync.PairedPeer
import com.universalmusic.player.data.sync.RemoteCommand
import com.universalmusic.player.data.sync.RemoteHostReply
import com.universalmusic.player.data.sync.RemoteLinkTransport
import com.universalmusic.player.data.sync.RemoteLinkHost
import com.universalmusic.player.data.sync.RemotePollReply
import com.universalmusic.player.data.sync.RemotePollRequest
import com.universalmusic.player.data.sync.RemoteStatus
import com.universalmusic.player.data.sync.RemoteTransferFailure
import com.universalmusic.player.data.sync.RemoteTransferReply
import com.universalmusic.player.data.sync.RemoteTransferSnapshot
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.ListeningHandoff
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PlayerSession
import com.universalmusic.player.domain.playback.RemotePlayerAdapter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControlTest {
    private val phone = PairedPeer("phone", "Poco", CertFingerprint.parse("ab".repeat(32))!!, pairedAtMs = 1)

    @Test
    fun lateDisagreeingStatusDoesNotUndoOptimisticPress() {
        val pending = RemotePlayIntent("phone", false, 100, accepted = true)
        val old = RemoteDeviceView("phone", "Phone", true, true, RemoteStatus(isPlaying = true), 90, null)
        assertFalse(pending.reconciles(old))
        assertFalse(pending.reconciles(old.copy(statusAtMs = 120)))
        assertFalse(pending.display(old).status!!.isPlaying)
        assertTrue(pending.reconciles(old.copy(status = RemoteStatus(isPlaying = false), statusAtMs = 120)))
        assertFalse(pending.reconciles(old.copy(status = RemoteStatus(isPlaying = false), statusAtMs = 90)))
    }

    @Test
    fun doublePressUsesTheIntendedStateAndTimeoutFallsBack() = runTest {
        val rig = hubRig()
        rig.phonePolls(RemoteStatus(isPlaying = true))
        rig.control.select("phone")
        runCurrent()
        rig.control.send(RemoteCommand.Pause)
        runCurrent()
        assertFalse(rig.control.state.value.selected!!.status!!.isPlaying)
        rig.control.send(RemoteCommand.Play)
        runCurrent()
        assertTrue(rig.control.state.value.selected!!.status!!.isPlaying)
        advanceTimeBy(4_001)
        runCurrent()
        assertTrue(rig.control.state.value.selected!!.status!!.isPlaying)
        assertEquals("Poco didn't confirm", rig.control.state.value.message?.text)
    }

    @Test
    fun theLinkIsWantedWhileVisibleOrPlayingAndTenMinutesAfter() = runTest {
        val visible = MutableStateFlow(false)
        val active = MutableStateFlow(false)
        val seen = mutableListOf<Pair<Long, Boolean>>()
        backgroundScope.launch { remoteLinkWanted(visible, active).collect { seen += testScheduler.currentTime to it } }
        runCurrent()
        active.value = true
        runCurrent()
        advanceTimeBy(1_000)
        active.value = false
        runCurrent()
        advanceTimeBy(REMOTE_LINK_GRACE_MS - 1)
        visible.value = true
        runCurrent()
        visible.value = false
        runCurrent()
        advanceTimeBy(REMOTE_LINK_GRACE_MS + 1)

        assertEquals(listOf(0L to false, 0L to true, 1_000L + 2 * REMOTE_LINK_GRACE_MS - 1 to false), seen)
    }

    @Test
    fun aHubSeesItsPhoneAndReportsACommandNobodyFetched() = runTest {
        val rig = hubRig()
        rig.phonePolls(RemoteStatus(title = "Phone song", isPlaying = true))
        runCurrent()

        val device = rig.control.state.value.devices.single()
        assertTrue(device.connected)
        assertEquals("Phone song", device.status?.title)
        assertTrue(rig.control.state.value.available)

        rig.control.select("phone")
        runCurrent()
        assertEquals("phone", rig.control.state.value.selected?.deviceId)
        rig.control.send(RemoteCommand.Pause)
        advanceTimeBy(6_000)
        assertEquals("Poco didn't respond", rig.control.state.value.message?.text)
    }

    @Test
    fun playHereLoadsThePhoneQueueAndSelectsThisDevice() = runTest {
        val rig = hubRig()
        val first = rig.phonePolls(RemoteStatus(title = "Phone song", isPlaying = true))
        rig.control.select("phone")
        runCurrent()

        rig.control.playHere()
        runCurrent()
        assertTrue(rig.control.state.value.transferring)
        val delivered = rig.phonePolls(RemoteStatus(title = "Phone song"), generation = 2, epoch = first.epoch)
        val envelope = delivered.commands.single()
        assertEquals(RemoteCommand.PauseForTransfer, envelope.command)
        val snapshot = RemoteTransferSnapshot(listOf(item("x"), item("y")), currentItemId = "q-y", positionMs = 5_000)
        rig.host.receiveTransfer(phone, first.epoch, envelope.seq, RemoteTransferReply(snapshot))
        runCurrent()

        assertEquals("q-y", rig.session.queueState.value.current?.id)
        assertTrue(rig.session.nowPlaying.value.isPlaying)
        assertNull(rig.control.state.value.selectedId)
        assertFalse(rig.control.state.value.transferring)
    }

    @Test
    fun playHereReportsAQueueThatCannotMove() = runTest {
        val rig = hubRig()
        val first = rig.phonePolls(RemoteStatus(title = "Phone song"))
        rig.control.select("phone")
        runCurrent()

        rig.control.playHere()
        runCurrent()
        val envelope = rig.phonePolls(RemoteStatus(title = "Phone song"), generation = 2, epoch = first.epoch).commands.single()
        rig.host.receiveTransfer(phone, first.epoch, envelope.seq, RemoteTransferReply(failure = RemoteTransferFailure.UNSUPPORTED_TRACK))
        runCurrent()

        assertEquals("This queue can't move here", rig.control.state.value.message?.text)
        assertEquals("phone", rig.control.state.value.selectedId)
        assertTrue(rig.session.queueState.value.items.isEmpty())
    }

    @Test
    fun theHubPublishesItsPlayerOnlyOnceTheGuiEnablesIt() = runTest {
        val rig = hubRig(guiEnabled = false)
        rig.session.play(listOf(PersistedTrack("yt:a", "PC song", sources = listOf(PersistedSource("YOUTUBE_MUSIC", "a"))).toDomain()))
        runCurrent()
        assertNull(rig.phonePolls(RemoteStatus()).hubStatus)

        rig.gui.value = true
        runCurrent()
        val reply = rig.phonePolls(RemoteStatus(), generation = 2)
        assertEquals("PC song", reply.hubStatus?.title)
        assertTrue(reply.hubHasPlayer)
    }

    @Test
    fun rolesFollowHomeSyncSettings() {
        val paired = AppSettings(homeLanSyncEnabled = true, homeLanSyncHub = phone)
        assertEquals(RemoteRole.HUB, paired.remoteRole(isDesktop = true))
        assertEquals(RemoteRole.CLIENT, paired.remoteRole(isDesktop = false))
        assertEquals(RemoteRole.CLIENT, paired.copy(homeLanSyncRole = HomeLanSyncRole.CLIENT).remoteRole(isDesktop = true))
        assertEquals(RemoteRole.NONE, paired.copy(homeLanSyncEnabled = false).remoteRole(isDesktop = false))
        assertEquals(RemoteRole.NONE, AppSettings(homeLanSyncEnabled = true).remoteRole(isDesktop = false))
    }

    @Test
    fun slowArtworkDoesNotBlockStatusOrRestartOnEveryTick() = runTest {
        var fetches = 0
        val fetched = CompletableDeferred<Unit>()
        val transport = object : RemoteLinkTransport {
            override suspend fun poll(request: RemotePollRequest): RemotePollReply {
                if (request.knownHubVersion >= 1) awaitCancellation()
                return RemotePollReply("e1", hubStatus = RemoteStatus(title = "Slow cover song", artworkKey = "abcd1234"), hubVersion = 1, hubHasPlayer = true)
            }
            override suspend fun command(command: RemoteCommand) = error("unused")
            override suspend fun postTransfer(epoch: String, seq: Long, reply: RemoteTransferReply) = Unit
            override suspend fun putArtwork(key: String, bytes: ByteArray) = Unit
            override suspend fun fetchArtwork(key: String): ByteArray? {
                fetches++
                delay(2_500)
                fetched.complete(Unit)
                return null
            }
            override fun close() = Unit
        }
        val rig = hubRig(clientTransport = transport)
        runCurrent()
        val first = rig.control.state.value.devices.single()
        assertTrue(first.connected)
        assertEquals("Slow cover song", first.status?.title)
        assertNull(first.artwork)
        advanceTimeBy(2_501)
        runCurrent()
        assertTrue(fetched.isCompleted)
        assertEquals(1, fetches)
        assertEquals("Slow cover song", rig.control.state.value.devices.single().status?.title)
    }

    private class Rig(
        val scope: TestScope,
        val control: RemoteControl,
        val host: RemoteLinkHost,
        val session: PlayerSession,
        val gui: MutableStateFlow<Boolean>,
    ) {
        suspend fun phonePolls(status: RemoteStatus, generation: Long = 1, epoch: String? = null): RemotePollReply {
            val reply = scope.async {
                host.poll(PairedPeer("phone", "Poco", CertFingerprint.parse("ab".repeat(32))!!, pairedAtMs = 1),
                    RemotePollRequest("phone", "link", generation, epoch, 0, -7, status))
            }
            scope.runCurrent()
            return (reply.await() as RemoteHostReply.Ok).value
        }
    }

    private suspend fun TestScope.hubRig(guiEnabled: Boolean = true, clientTransport: RemoteLinkTransport? = null): Rig {
        val clock = { testScheduler.currentTime }
        val session = PlayerSession(PlayingEngine(), DefaultSourceResolver(), backgroundScope, clock = clock)
        val host = RemoteLinkHost(hubName = { "Desk" }, clock = clock, newEpoch = { "e1" })
        host.start()
        val gui = MutableStateFlow(guiEnabled)
        val control = RemoteControl(
            scope = backgroundScope,
            settings = MutableStateFlow(AppSettings(homeLanSyncEnabled = true, homeLanSyncRole = if (clientTransport == null) HomeLanSyncRole.HUB else HomeLanSyncRole.CLIENT, homeLanSyncPeers = listOf(phone), homeLanSyncHub = phone)),
            isDesktop = true,
            host = host,
            player = RemotePlayerAdapter(session, backgroundScope, clock),
            handoff = ListeningHandoff(NoExchange, session, localTracks = { emptyList() }, clock = clock),
            guiPlayer = gui,
            linkWanted = MutableStateFlow(clientTransport != null),
            clientTarget = { DeliveryTarget(phone, "pc", phone.fingerprint) },
            openLink = { clientTransport ?: error("a hub opens no link") },
            onLinkConnected = {},
            clock = clock,
        )
        runCurrent()
        return Rig(this, control, host, session, gui)
    }
}

private fun item(id: String) = PersistedQueueItem("q-$id", PersistedTrack("yt:$id", "Track $id", sources = listOf(PersistedSource("YOUTUBE_MUSIC", id))))

private class PlayingEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        state.value = EngineState(EngineStatus.PLAYING, durationMs = 200_000, playGeneration = playGeneration)
    }
    override fun pause() { state.value = state.value.copy(status = EngineStatus.PAUSED) }
    override fun resume() { state.value = state.value.copy(status = EngineStatus.PLAYING) }
    override fun seekTo(positionMs: Long) { state.value = state.value.copy(positionMs = positionMs) }
    override fun stop() { state.value = state.value.copy(status = EngineStatus.IDLE) }
    override fun setVolume(volume: Float) = Unit
}

private object NoExchange : com.universalmusic.player.data.session.ListeningExchangeStore {
    override suspend fun refusedPairing() = null
    override suspend fun setRefusedPairing(pairing: com.universalmusic.player.data.session.ListeningPairingIdentity?) = Unit
    override suspend fun readLocalExport() = null
    override suspend fun readLocalQueue(hash: com.universalmusic.player.data.session.QueueHash) = null
    override suspend fun receivePeer(peer: com.universalmusic.player.data.session.PeerListening) =
        com.universalmusic.player.data.session.PeerReceipt(stored = false, needsQueue = false)
    override suspend fun attachPeerQueue(deviceId: String, queue: com.universalmusic.player.data.session.PortableListeningQueue) = false
    override suspend fun readPeer(deviceId: String) = null
    override suspend fun deliveredRevision(hubDeviceId: String) = null
    override suspend fun markDelivered(hubDeviceId: String, revision: com.universalmusic.player.data.session.PublicationRevision) = false
    override suspend fun appliedHandoff(deviceId: String) = null
    override suspend fun claimHandoff(
        request: com.universalmusic.player.data.session.HandoffRequest,
        accept: suspend () -> com.universalmusic.player.data.session.ListeningCommit?,
    ) = com.universalmusic.player.data.session.HandoffClaim.INSTALL_REJECTED
}
