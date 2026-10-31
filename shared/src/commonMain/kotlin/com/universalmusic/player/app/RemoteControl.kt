package com.universalmusic.player.app

import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.sync.DeliveryTarget
import com.universalmusic.player.data.sync.HomeLanSyncRole
import com.universalmusic.player.data.sync.RemoteArtworkReader
import com.universalmusic.player.data.sync.RemoteClientSend
import com.universalmusic.player.data.sync.RemoteCommand
import com.universalmusic.player.data.sync.RemoteHostTransfer
import com.universalmusic.player.data.sync.RemoteLinkClient
import com.universalmusic.player.data.sync.RemoteLinkHost
import com.universalmusic.player.data.sync.RemotePlayer
import com.universalmusic.player.data.sync.RemoteSendOutcome
import com.universalmusic.player.data.sync.RemoteStatus
import com.universalmusic.player.data.sync.RemoteTransferFailure
import com.universalmusic.player.data.sync.RemoteTransferReply
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.playback.ListeningHandoff
import com.universalmusic.player.domain.playback.TransferLoad
import com.universalmusic.player.platform.PlaybackTrace
import com.universalmusic.player.platform.cacheRemoteArtwork
import com.universalmusic.player.platform.currentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Another device this one can show and control. [connected] is live; [status] is null while it plays nothing. */
data class RemoteDeviceView(
    val deviceId: String,
    val name: String,
    val connected: Boolean,
    /** False when the device's process has no player, such as a PC running only the Home sync hub. */
    val hasPlayer: Boolean,
    val status: RemoteStatus?,
    /** This device's clock when [status] arrived, for extrapolating a playing position. */
    val statusAtMs: Long,
    val artwork: Artwork?,
)

data class RemoteMessage(val id: Long, val text: String)

data class RemoteControlState(
    /** Home sync is on and this device is paired with another one. */
    val available: Boolean = false,
    val thisDeviceName: String = "This device",
    val thisIsDesktop: Boolean = false,
    val devices: List<RemoteDeviceView> = emptyList(),
    /** The device Now Playing shows and controls; null is this one. */
    val selectedId: String? = null,
    val transferring: Boolean = false,
    val message: RemoteMessage? = null,
) {
    val selected: RemoteDeviceView? get() = devices.firstOrNull { it.deviceId == selectedId }
}

/**
 * Shows and controls the paired device's player over the remote link. A desktop that owns the hub
 * reaches its phones through [host]; a client device reaches the hub through its own link. Local
 * shortcuts, MPRIS and the media session keep controlling this device's player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteControl internal constructor(
    private val scope: CoroutineScope,
    private val settings: StateFlow<AppSettings>,
    private val isDesktop: Boolean,
    private val host: RemoteLinkHost,
    private val player: RemotePlayer,
    private val handoff: ListeningHandoff,
    private val guiPlayer: StateFlow<Boolean>,
    linkWanted: Flow<Boolean>,
    clientTarget: () -> DeliveryTarget?,
    openLink: suspend (com.universalmusic.player.data.sync.PairedPeer) -> com.universalmusic.player.data.sync.RemoteLinkTransport,
    onLinkConnected: () -> Unit,
    pressable: () -> Boolean = { true },
    private val clock: () -> Long = ::currentTimeMillis,
) {
    private val artworkReader = RemoteArtworkReader()
    private val client = RemoteLinkClient(
        scope = scope,
        target = clientTarget,
        connect = openLink,
        player = player,
        artwork = artworkReader,
        onConnected = onLinkConnected,
        clock = clock,
        pressable = pressable,
    )

    private val intent = MutableStateFlow<RemotePlayIntent?>(null)
    private var intentJob: Job? = null
    private val selection = MutableStateFlow<String?>(null)
    private val transferring = MutableStateFlow(false)
    private val message = MutableStateFlow<RemoteMessage?>(null)
    private var messages = 0L
    private val artworkCache = ArtworkFiles(scope)

    private val role: StateFlow<RemoteRole> = settings.map { it.remoteRole(isDesktop) }
        .distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, RemoteRole.NONE)

    private val devices: StateFlow<List<RemoteDeviceView>> = role.flatMapLatest { side ->
        when (side) {
            RemoteRole.NONE -> flowOf(emptyList())
            RemoteRole.HUB -> hubDevices()
            RemoteRole.CLIENT -> clientDevices()
        }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<RemoteControlState> = combine(
        role, combine(devices, intent) { views, pending -> views.map { view -> pending?.display(view) ?: view } }, selection, transferring, message,
    ) { side, views, selected, moving, note ->
        RemoteControlState(
            available = side != RemoteRole.NONE && views.isNotEmpty(),
            thisDeviceName = if (isDesktop) "This PC" else "This phone",
            thisIsDesktop = isDesktop,
            devices = views,
            selectedId = selected?.takeIf { id -> views.any { it.deviceId == id } },
            transferring = moving,
            message = note,
        )
    }.stateIn(scope, SharingStarted.Eagerly, RemoteControlState(thisDeviceName = if (isDesktop) "This PC" else "This phone", thisIsDesktop = isDesktop))

    init {
        // Publish this GUI's player to polling devices. A process that never enables the GUI player
        // (`--hub-only`) keeps advertising none.
        scope.launch {
            combine(player.state, guiPlayer) { state, enabled -> state to enabled }.collect { (state, enabled) ->
                val status = artworkReader.wireStatus(state, clock())
                val bytes = state?.localArtwork?.let { artworkReader.bytesFor(it) }
                host.updateHubStatus(status, hasPlayer = enabled, artwork = status?.artworkKey?.let { key -> bytes?.let { key to it } })
            }
        }
        scope.launch {
            devices.collect { views ->
                intent.update { pending ->
                    pending?.takeUnless { next -> views.firstOrNull { it.deviceId == next.deviceId }?.let(next::reconciles) == true }
                }
            }
        }
        client.run(
            combine(role, guiPlayer, linkWanted) { side, enabled, wanted -> side == RemoteRole.CLIENT && enabled && wanted },
        )
    }

    /** The raw visible edge, which linkWanted's grace can hide. Its poll restarts the lease refresh. */
    fun onAppVisible() {
        if (role.value == RemoteRole.CLIENT) client.reconnectNow()
    }

    fun select(deviceId: String?) {
        selection.value = deviceId
    }

    fun dismissMessage(id: Long) {
        message.update { if (it?.id == id) null else it }
    }

    fun send(command: RemoteCommand) {
        val target = state.value.selected ?: return
        val pending = if (command == RemoteCommand.Play || command == RemoteCommand.Pause) {
            RemotePlayIntent(target.deviceId, command == RemoteCommand.Play, clock()).also {
                intent.value = it
                intentJob?.cancel()
                intentJob = scope.launch {
                    delay(4_000)
                    val current = intent.value
                    if (current?.pressedAtMs == it.pressedAtMs && current.deviceId == it.deviceId && current.playing == it.playing && intent.compareAndSet(current, null)) report("${target.name} didn't confirm")
                }
            }
        } else null
        scope.launch {
            val problem = when (role.value) {
                RemoteRole.HUB -> {
                    if (pending != null && !host.peer(target.deviceId).connected) {
                        withTimeoutOrNull(4_000) { host.changes.first { host.peer(target.deviceId).connected } }
                        if (intent.value != pending) return@launch
                    }
                    host.send(target.deviceId, command, pending?.let { (clock() - it.pressedAtMs).coerceAtLeast(0) } ?: 0).problem(target.name)
                }
                RemoteRole.CLIENT -> client.send(command).problem(target.name)
                RemoteRole.NONE -> null
            }
            if (pending != null && problem == null) {
                intent.update { current ->
                    if (current != pending) current else pending.copy(accepted = true).takeUnless { accepted ->
                        devices.value.firstOrNull { it.deviceId == accepted.deviceId }?.let(accepted::reconciles) == true
                    }
                }
            }
            if (problem != null && (pending == null || intent.compareAndSet(pending, null))) report(problem)
        }
    }

    /** Pauses the selected device and plays its queue here. */
    fun playHere() {
        val target = state.value.selected ?: return
        if (!transferring.compareAndSet(expect = false, update = true)) return
        scope.launch {
            try {
                val (reply, problem) = when (role.value) {
                    RemoteRole.HUB -> when (val result = host.requestTransfer(target.deviceId)) {
                        is RemoteHostTransfer.Received -> result.reply to null
                        is RemoteHostTransfer.Failed -> null to result.outcome.problem(target.name)
                    }
                    RemoteRole.CLIENT -> when (val sent = client.send(RemoteCommand.PauseForTransfer)) {
                        is RemoteClientSend.Applied -> sent.reply.transfer to null
                        else -> null to sent.problem(target.name)
                    }
                    RemoteRole.NONE -> null to null
                }
                when {
                    problem != null -> report(problem)
                    reply != null -> load(reply, target.name)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (err: Exception) {
                PlaybackTrace.log("Remote", "play here failed: ${err.message ?: err}")
                report("Couldn't move playback from ${target.name}")
            } finally {
                transferring.value = false
            }
        }
    }

    private suspend fun load(reply: RemoteTransferReply, from: String) {
        val snapshot = reply.snapshot
        if (snapshot == null) {
            report(if (reply.failure == RemoteTransferFailure.EMPTY) "Nothing is playing on $from" else QUEUE_CANT_MOVE)
            return
        }
        when (handoff.loadTransfer(snapshot)) {
            TransferLoad.PLAYING -> selection.value = null
            TransferLoad.UNSUPPORTED, TransferLoad.EMPTY -> report(QUEUE_CANT_MOVE)
        }
    }

    private fun report(text: String) {
        message.value = RemoteMessage(++messages, text)
    }

    private fun hubDevices(): Flow<List<RemoteDeviceView>> =
        combine(settings.map { it.homeLanSyncPeers }.distinctUntilChanged(), host.changes, ticker(), artworkCache.changes) { peers, _, _, _ -> peers }
            .transformLatest { peers ->
                emit(peers.map { peer ->
                    val view = host.peer(peer.deviceId)
                    val status = view.status
                    RemoteDeviceView(
                        deviceId = peer.deviceId,
                        name = peer.displayName,
                        connected = view.connected,
                        hasPlayer = true,
                        status = status,
                        // The host stamps statuses with this same process clock.
                        statusAtMs = view.statusAtMs,
                        artwork = artworkFor(status) { key -> host.peerArtwork(peer.deviceId, key) },
                    )
                })
            }

    private fun clientDevices(): Flow<List<RemoteDeviceView>> =
        combine(settings.map { it.homeLanSyncHub }.distinctUntilChanged(), client.state, ticker(), artworkCache.changes) { hub, link, _, _ -> hub to link }
            .transformLatest { (hub, link) ->
                if (hub == null) {
                    emit(emptyList())
                    return@transformLatest
                }
                val status = link.hubStatus.takeIf { link.hubHasPlayer }
                emit(listOf(
                    RemoteDeviceView(
                        deviceId = hub.deviceId,
                        name = link.hubName?.takeIf { it.isNotBlank() } ?: hub.displayName,
                        connected = link.connected,
                        hasPlayer = !link.connected || link.hubHasPlayer,
                        status = status,
                        statusAtMs = link.hubStatusAtMs,
                        artwork = artworkFor(status) { key -> client.fetchHubArtwork(key) },
                    ),
                ))
            }

    private suspend fun artworkFor(status: RemoteStatus?, fetch: suspend (String) -> ByteArray?): Artwork? {
        status ?: return null
        status.artworkUrl?.let { return Artwork(it) }
        val key = status.artworkKey ?: return null
        return artworkCache.uriFor(key, fetch)?.let(::Artwork)
    }

    private fun ticker(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(DEVICE_REFRESH_MS)
        }
    }

    /** Remembers which artwork keys are cached as files, so a key is fetched at most once while it works. */
    private class ArtworkFiles(private val scope: CoroutineScope) {
        private val lock = Mutex()
        private val uris = LinkedHashMap<String, String>()
        private val fetching = HashSet<String>()
        val changes = MutableStateFlow(0L)

        suspend fun uriFor(key: String, fetch: suspend (String) -> ByteArray?): String? = lock.withLock {
            uris[key]?.let { return@withLock it }
            if (fetching.add(key)) scope.launch {
                try {
                    val uri = fetch(key)?.let { cacheRemoteArtwork(key, it) }
                    if (uri != null) lock.withLock {
                        uris[key] = uri
                        while (uris.size > 16) uris.remove(uris.keys.first())
                        changes.update { it + 1 }
                    }
                } finally {
                    lock.withLock { fetching.remove(key) }
                }
            }
            // Publish the status with a placeholder while a single independent fetch runs.
            // A miss is retried on a later refresh, after the hub has received the bytes.
            null
        }
    }

    private companion object {
        const val DEVICE_REFRESH_MS = 1_000L
        const val QUEUE_CANT_MOVE = "This queue can't move here"
    }
}

internal enum class RemoteRole { NONE, HUB, CLIENT }

internal fun AppSettings.remoteRole(isDesktop: Boolean): RemoteRole = when {
    !homeLanSyncEnabled || homeLanSyncIdentityResetPending -> RemoteRole.NONE
    homeLanSyncRole == HomeLanSyncRole.HUB || (homeLanSyncRole == HomeLanSyncRole.AUTO && isDesktop) -> RemoteRole.HUB
    homeLanSyncHub != null -> RemoteRole.CLIENT
    else -> RemoteRole.NONE
}

private fun RemoteSendOutcome.problem(name: String): String? = when (this) {
    RemoteSendOutcome.DELIVERED -> null
    RemoteSendOutcome.UNREACHABLE -> "$name isn't reachable"
    RemoteSendOutcome.NOT_DELIVERED -> "$name didn't respond"
    RemoteSendOutcome.UNCONFIRMED -> "$name didn't confirm"
    RemoteSendOutcome.STOPPED -> "Home sync stopped"
}

private fun RemoteClientSend.problem(name: String): String? = when (this) {
    is RemoteClientSend.Applied -> null
    RemoteClientSend.NoPlayer -> "$name is running Home sync without the player"
    RemoteClientSend.Unreachable -> "$name didn't respond"
}

/**
 * Whether this device should keep its link to the hub open: while the app is visible, while it
 * plays or buffers, and for [graceMs] after it last did either. It never starts on its own.
 */
internal fun remoteLinkWanted(visible: Flow<Boolean>, active: Flow<Boolean>, graceMs: Long = REMOTE_LINK_GRACE_MS): Flow<Boolean> {
    var wasOn = false
    return combine(visible, active) { shown, playing -> shown || playing }
        .distinctUntilChanged()
        .transformLatest { on ->
            when {
                on -> {
                    wasOn = true
                    emit(true)
                }
                wasOn -> {
                    emit(true)
                    delay(graceMs)
                    emit(false)
                }
                else -> emit(false)
            }
        }
        .distinctUntilChanged()
}

internal const val REMOTE_LINK_GRACE_MS = 10L * 60 * 1000

/** One pending explicit button intent. A disagreeing old snapshot cannot undo a press. */
internal data class RemotePlayIntent(val deviceId: String, val playing: Boolean, val pressedAtMs: Long, val accepted: Boolean = false) {
    fun reconciles(view: RemoteDeviceView): Boolean = accepted && view.deviceId == deviceId &&
        view.statusAtMs > pressedAtMs && view.status?.let { (it.isPlaying || it.buffering) == playing } == true

    fun display(view: RemoteDeviceView): RemoteDeviceView = if (view.deviceId != deviceId) view else
        view.copy(status = view.status?.copy(isPlaying = playing, buffering = false))
}
