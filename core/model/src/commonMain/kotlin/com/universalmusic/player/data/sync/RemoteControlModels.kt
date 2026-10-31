package com.universalmusic.player.data.sync

import com.universalmusic.player.data.session.PersistedQueueItem
import kotlin.time.TimeMark
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A command older than this on the hub clock is never delivered, and the receiver drops it too. */
const val REMOTE_COMMAND_DEADLINE_MS = 4_000L

/** How long the hub holds a poll that has nothing to say. */
const val REMOTE_POLL_HOLD_MS = 20_000L

const val REMOTE_QUEUE_CAP = 200

const val REMOTE_ARTWORK_MAX_BYTES = 1 shl 20

/** A position that moves further than this from extrapolation counts as a seek, not a tick. */
const val REMOTE_SEEK_JUMP_MS = 2_000L

/**
 * One device's player as another device sees it. Positions travel with an age measured on the
 * sender ([positionAgeMs]), never an absolute time, because the clocks differ.
 */
@Serializable
data class RemoteStatus(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    /** http(s) only; never file: or content:. */
    val artworkUrl: String? = null,
    /** Set when local artwork bytes are available through the artwork route. */
    val artworkKey: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long = 0,
    val positionAgeMs: Long = 0,
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RemoteRepeat = RemoteRepeat.OFF,
    val volume: Float = 1f,
    val currentItemId: String? = null,
    /** Playback order starting at the current entry, capped at [REMOTE_QUEUE_CAP]. */
    val queue: List<RemoteQueueEntry> = emptyList(),
    val canSkipPrevious: Boolean = false,
    val canSkipNext: Boolean = false,
)

@Serializable
data class RemoteQueueEntry(val itemId: String, val title: String, val artist: String? = null, val durationMs: Long? = null)

@Serializable
enum class RemoteRepeat { OFF, ALL, ONE }

/** Serialized with the class discriminator `type`. */
@Serializable
sealed interface RemoteCommand {
    @Serializable @SerialName("toggle_play")
    data object TogglePlay : RemoteCommand

    @Serializable @SerialName("play")
    data object Play : RemoteCommand

    @Serializable @SerialName("pause")
    data object Pause : RemoteCommand

    @Serializable @SerialName("next")
    data object Next : RemoteCommand

    @Serializable @SerialName("previous")
    data object Previous : RemoteCommand

    @Serializable @SerialName("seek")
    data class SeekTo(val positionMs: Long) : RemoteCommand

    @Serializable @SerialName("volume")
    data class SetVolume(val volume: Float) : RemoteCommand

    @Serializable @SerialName("shuffle")
    data class SetShuffle(val enabled: Boolean) : RemoteCommand

    @Serializable @SerialName("repeat")
    data class SetRepeat(val mode: RemoteRepeat) : RemoteCommand

    @Serializable @SerialName("play_item")
    data class PlayItem(val itemId: String) : RemoteCommand

    @Serializable @SerialName("play_next")
    data class PlayNext(val itemId: String) : RemoteCommand

    /** Pauses the source and captures a [RemoteTransferSnapshot] in the same session-thread block. */
    @Serializable @SerialName("pause_for_transfer")
    data object PauseForTransfer : RemoteCommand
}

/** [ageMs] is hub time since the command was queued, measured when the reply left the hub. */
@Serializable
data class RemoteEnvelope(val seq: Long, val ageMs: Long, val command: RemoteCommand)

@Serializable
data class RemotePollRequest(
    val deviceId: String,
    /**
     * Random per client process. A new id means the device restarted: generations start over and
     * commands the old process may have run are never offered again.
     */
    val linkId: String,
    /** Increases with every poll this client process sends; an older one is superseded. */
    val generation: Long,
    /** The epoch [ackSeq] and [knownHubVersion] belong to; null before the first reply. */
    val epoch: String? = null,
    val ackSeq: Long = 0,
    val knownHubVersion: Long = -1,
    val status: RemoteStatus? = null,
    /** Commands the client skipped because they arrived past [REMOTE_COMMAND_DEADLINE_MS]. */
    val droppedSeqs: List<Long> = emptyList(),
)

@Serializable
data class RemotePollReply(
    val epoch: String,
    val commands: List<RemoteEnvelope> = emptyList(),
    val hubStatus: RemoteStatus? = null,
    val hubVersion: Long = 0,
    val hubName: String = "",
    /** How long the hub held this request before replying. */
    val heldMs: Long = 0,
    /** False for a `--hub-only` process: it has no player to show or control. */
    val hubHasPlayer: Boolean = false,
    /** A newer poll from the same device, or a stopping hub, ended this one; it carries nothing. */
    val superseded: Boolean = false,
    /** Optional receiver-issued four-second lease; absent on older hubs. */
    val directIntentLease: String? = null,
)

/** The portable queue a source device hands over for "Play here". */
@Serializable
data class RemoteTransferSnapshot(
    val items: List<PersistedQueueItem>,
    val currentItemId: String,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeat: RemoteRepeat = RemoteRepeat.OFF,
)

@Serializable
enum class RemoteTransferFailure { EMPTY, UNSUPPORTED_TRACK, INVALID_ID, DUPLICATE_ITEM_ID }

/** Exactly one of [snapshot] and [failure] is set. */
@Serializable
data class RemoteTransferReply(val snapshot: RemoteTransferSnapshot? = null, val failure: RemoteTransferFailure? = null)

@Serializable
data class RemoteCommandReply(
    val status: RemoteStatus? = null,
    val transfer: RemoteTransferReply? = null,
    /** The hub dropped a direct intent unapplied because its lease or age was invalid. */
    val leaseRejected: Boolean = false,
)

/**
 * A player's state for the remote link. [status] has a zero position age as of [capturedAtMs];
 * [localArtwork] is the file: / content: location whose bytes back [RemoteStatus.artworkKey].
 */
data class RemotePlayerState(val status: RemoteStatus, val capturedAtMs: Long, val localArtwork: String? = null)

class RemoteCommandExpired : IllegalStateException("Remote command expired before application")

/** The player a remote device controls. Only a GUI process exposes one. */
interface RemotePlayer {
    /** Null while nothing is loaded. */
    val state: StateFlow<RemotePlayerState?>

    /**
     * Checks [deadline], applies [command] and captures status in one session-thread step.
     * Throws [RemoteCommandExpired] if the deadline passed before mutation.
     */
    suspend fun apply(command: RemoteCommand, deadline: TimeMark? = null): RemoteCommandReply
}

/** The status as sent at [nowMs]: its position age grows with time since capture. */
fun RemotePlayerState.toWire(nowMs: Long): RemoteStatus =
    status.copy(positionAgeMs = (nowMs - capturedAtMs).coerceAtLeast(0) + status.positionAgeMs)

/** Where the position is at [elapsedMs] after the status was received; a paused position stays put. */
fun RemoteStatus.positionAfter(elapsedMs: Long): Long {
    if (!isPlaying) return positionMs
    val extrapolated = positionMs + positionAgeMs + elapsedMs.coerceAtLeast(0)
    return durationMs?.let { extrapolated.coerceAtMost(it) } ?: extrapolated
}

/**
 * True when [next] differs from this status in more than a position tick: track, play state,
 * shuffle, repeat, volume, queue, artwork, or a position that jumped past [REMOTE_SEEK_JUMP_MS]
 * from extrapolation over [elapsedMs].
 */
fun RemoteStatus?.isMaterialChange(next: RemoteStatus?, elapsedMs: Long): Boolean {
    if (this == null || next == null) return this != next
    if (copy(positionMs = 0, positionAgeMs = 0) != next.copy(positionMs = 0, positionAgeMs = 0)) return true
    return kotlin.math.abs(next.positionAfter(0) - positionAfter(elapsedMs)) > REMOTE_SEEK_JUMP_MS
}

@Serializable
data class RemoteDirectIntent(
    val command: RemoteCommand,
    val senderId: String,
    val seq: Long,
    val ageMs: Long,
    /** Null for a single unretried attempt: ordered by [seq], with no transit bound to allow a retry. */
    val lease: String? = null,
)
