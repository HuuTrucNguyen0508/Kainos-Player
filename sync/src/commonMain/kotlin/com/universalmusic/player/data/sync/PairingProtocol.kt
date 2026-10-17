package com.universalmusic.player.data.sync

import com.universalmusic.player.platform.currentTimeMillis
import com.universalmusic.player.platform.secureRandomBytes
import com.universalmusic.player.platform.sha256Bytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

const val HOME_LAN_SYNC_PATH_PAIR_START = "/kainos-sync/v3/pair/start"
const val HOME_LAN_SYNC_PATH_PAIR_REVEAL = "/kainos-sync/v3/pair/reveal"
const val HOME_LAN_SYNC_PATH_PAIR_CONFIRM = "/kainos-sync/v3/pair/confirm"
const val HOME_LAN_SYNC_PATH_PAIR_CANCEL = "/kainos-sync/v3/pair/cancel"
const val HOME_LAN_SYNC_PATH_PAIR_STATUS = "/kainos-sync/v3/pair/status"

const val PAIRING_NONCE_BYTES = 32
const val PAIRING_ATTEMPT_TTL_MS = 2L * 60L * 1000L
const val PAIRING_STARTS_PER_MINUTE = 5

@Serializable
data class PairStartRequest(val deviceId: String, val displayName: String, val commitHex: String)

@Serializable
data class PairStartResponse(val attemptId: String, val nonceHex: String)

@Serializable
data class PairRevealRequest(val attemptId: String, val nonceHex: String)

@Serializable
data class PairAttemptRequest(val attemptId: String)

@Serializable
enum class PairState { PENDING, PAIRED, ENDED }

@Serializable
data class PairStatusResponse(
    val state: PairState,
    val reason: String? = null,
    val hubDeviceId: String? = null,
)

enum class PairingRefusal(val httpStatus: Int) {
    MALFORMED(400),
    FOREIGN(403),
    BUSY(409),
    ENDED(410),
    RATE_LIMITED(429),
}

sealed interface PairingReply<out T> {
    data class Ok<T>(val value: T) : PairingReply<T>
    data class Refused(val refusal: PairingRefusal, val reason: String) : PairingReply<Nothing>
}

fun pairingCommit(nonce: ByteArray): ByteArray = sha256Bytes(nonce)

/** SHA-256("kainos-pair-v1" || fpHub || fpPhone || nonceH || nonceP), fingerprints as raw digests. */
fun pairingCode(
    hub: CertFingerprint,
    phone: CertFingerprint,
    hubNonce: ByteArray,
    phoneNonce: ByteArray,
): PairingCode = PairingCode.fromDigest(
    sha256Bytes("kainos-pair-v1".encodeToByteArray() + hub.bytes + phone.bytes + hubNonce + phoneNonce),
)

/**
 * The hub's side of pairing: one attempt at a time, bound to the client fingerprint from that
 * client's own TLS session. An attempt that ends never resumes; the phone starts a new one.
 */
class PairingHost(
    private val hubFingerprint: () -> CertFingerprint,
    private val hubDeviceId: () -> String,
    private val onPaired: suspend (PairedPeer) -> Unit,
    private val clock: () -> Long = ::currentTimeMillis,
    private val random: (Int) -> ByteArray = ::secureRandomBytes,
) {
    private sealed interface Phase {
        data object AwaitingReveal : Phase
        data class Revealed(val code: PairingCode, val pcConfirmed: Boolean, val phoneConfirmed: Boolean) : Phase
        data class Paired(val peer: PairedPeer) : Phase
        data class Ended(val reason: String) : Phase
    }

    private class Attempt(
        val id: String,
        val client: CertFingerprint,
        val deviceId: String,
        val name: String,
        val commit: ByteArray,
        val hubNonce: ByteArray,
        val startedAtMs: Long,
        var phase: Phase,
    )

    private val mutex = Mutex()
    private var latestAttempt: Attempt? = null
    private val recentStarts = ArrayDeque<Long>()
    private val _incoming = MutableStateFlow<PairingAttempt?>(null)
    val incoming: StateFlow<PairingAttempt?> = _incoming.asStateFlow()

    suspend fun start(client: CertFingerprint, request: PairStartRequest): PairingReply<PairStartResponse> =
        locked {
            if (latestAttempt?.isPending() == true) return@locked refused(PairingRefusal.BUSY, "Another pairing is in progress")
            val now = clock()
            while (recentStarts.firstOrNull()?.let { now - it >= 60_000L } == true) recentStarts.removeFirst()
            if (recentStarts.size >= PAIRING_STARTS_PER_MINUTE) {
                return@locked refused(PairingRefusal.RATE_LIMITED, "Too many pairing attempts; wait a minute")
            }
            val commit = request.commitHex.hexBytes(32)
            if (commit == null || request.deviceId.isBlank()) {
                return@locked refused(PairingRefusal.MALFORMED, "Malformed pairing start")
            }
            recentStarts.addLast(now)
            val attempt = Attempt(
                id = random(16).toHex(),
                client = client,
                deviceId = request.deviceId,
                name = request.displayName.ifBlank { "Unnamed device" }.take(64),
                commit = commit,
                hubNonce = random(PAIRING_NONCE_BYTES),
                startedAtMs = now,
                phase = Phase.AwaitingReveal,
            )
            latestAttempt = attempt
            publish()
            PairingReply.Ok(PairStartResponse(attempt.id, attempt.hubNonce.toHex()))
        }

    suspend fun reveal(client: CertFingerprint, request: PairRevealRequest): PairingReply<Unit> = locked {
        val attempt = owned(client, request.attemptId) ?: return@locked foreign()
        when (attempt.phase) {
            is Phase.Ended, is Phase.Paired -> return@locked ended(attempt)
            is Phase.Revealed -> return@locked refused(PairingRefusal.MALFORMED, "Nonce already revealed")
            Phase.AwaitingReveal -> Unit
        }
        val nonce = request.nonceHex.hexBytes(PAIRING_NONCE_BYTES)
        if (nonce == null || !constantTimeEquals(pairingCommit(nonce), attempt.commit)) {
            end(attempt, "The phone's pairing check did not match")
            return@locked ended(attempt)
        }
        attempt.phase = Phase.Revealed(
            pairingCode(hubFingerprint(), attempt.client, attempt.hubNonce, nonce),
            pcConfirmed = false,
            phoneConfirmed = false,
        )
        publish()
        PairingReply.Ok(Unit)
    }

    suspend fun confirmFromPhone(client: CertFingerprint, attemptId: String): PairingReply<Unit> = locked {
        val attempt = owned(client, attemptId) ?: return@locked foreign()
        confirm(attempt) { it.copy(phoneConfirmed = true) }
    }

    /** The PC's Confirm button; it names the attempt it showed, so a newer attempt is never confirmed by it. */
    suspend fun confirmFromPc(attemptId: String): PairingReply<Unit> = locked {
        val attempt = latestAttempt?.takeIf { it.id == attemptId } ?: return@locked foreign()
        confirm(attempt) { it.copy(pcConfirmed = true) }
    }

    suspend fun rejectFromPc(attemptId: String): PairingReply<Unit> = locked {
        val attempt = latestAttempt?.takeIf { it.id == attemptId } ?: return@locked foreign()
        if (!attempt.isPending()) return@locked ended(attempt)
        end(attempt, "The PC rejected the pairing")
        PairingReply.Ok(Unit)
    }

    suspend fun cancel(client: CertFingerprint, attemptId: String): PairingReply<Unit> = locked {
        val attempt = owned(client, attemptId) ?: return@locked foreign()
        if (!attempt.isPending()) return@locked ended(attempt)
        end(attempt, "The phone cancelled the pairing")
        PairingReply.Ok(Unit)
    }

    suspend fun status(client: CertFingerprint, attemptId: String): PairingReply<PairStatusResponse> = locked {
        val attempt = owned(client, attemptId) ?: return@locked foreign()
        PairingReply.Ok(
            when (val phase = attempt.phase) {
                is Phase.Paired -> PairStatusResponse(PairState.PAIRED, hubDeviceId = hubDeviceId())
                is Phase.Ended -> PairStatusResponse(PairState.ENDED, reason = phase.reason)
                else -> PairStatusResponse(PairState.PENDING)
            },
        )
    }

    /** Ends a pending attempt that ran out of time; the hub calls this on a timer. */
    suspend fun expireDue() = locked { Unit }

    /** Ends any pending attempt, for example when this PC's identity is reset. */
    suspend fun abandon(reason: String) = locked {
        latestAttempt?.takeIf { it.isPending() }?.let { end(it, reason) }
        Unit
    }

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        latestAttempt?.takeIf { it.isPending() && clock() - it.startedAtMs >= PAIRING_ATTEMPT_TTL_MS }
            ?.let { end(it, "The pairing timed out") }
        block()
    }

    private suspend fun confirm(attempt: Attempt, mark: (Phase.Revealed) -> Phase.Revealed): PairingReply<Unit> {
        val phase = attempt.phase
        if (phase !is Phase.Revealed) {
            return if (attempt.isPending()) refused(PairingRefusal.MALFORMED, "Nothing to confirm yet") else ended(attempt)
        }
        val next = mark(phase)
        if (next.pcConfirmed && next.phoneConfirmed) {
            val peer = PairedPeer(
                deviceId = attempt.deviceId,
                displayName = attempt.name,
                fingerprint = attempt.client,
                pairedAtMs = clock(),
            )
            onPaired(peer)
            attempt.phase = Phase.Paired(peer)
        } else {
            attempt.phase = next
        }
        publish()
        return PairingReply.Ok(Unit)
    }

    private fun owned(client: CertFingerprint, attemptId: String): Attempt? =
        latestAttempt?.takeIf { it.id == attemptId && it.client == client }

    private fun end(attempt: Attempt, reason: String) {
        attempt.phase = Phase.Ended(reason)
        publish()
    }

    private fun publish() {
        val attempt = latestAttempt?.takeIf { it.isPending() }
        _incoming.value = attempt?.let {
            val revealed = it.phase as? Phase.Revealed
            PairingAttempt(
                attemptId = it.id,
                peerDeviceId = it.deviceId,
                peerName = it.name,
                peerFingerprint = it.client,
                code = revealed?.code,
                pcConfirmed = revealed?.pcConfirmed == true,
                phoneConfirmed = revealed?.phoneConfirmed == true,
            )
        }
    }

    private fun Attempt.isPending() = phase is Phase.AwaitingReveal || phase is Phase.Revealed

    private fun foreign() = refused(PairingRefusal.FOREIGN, "Not this device's pairing attempt")

    private fun ended(attempt: Attempt): PairingReply.Refused =
        refused(PairingRefusal.ENDED, (attempt.phase as? Phase.Ended)?.reason ?: "The pairing already finished")

    private fun refused(refusal: PairingRefusal, reason: String) = PairingReply.Refused(refusal, reason)
}

internal fun String.hexBytes(expectedSize: Int): ByteArray? {
    if (length != expectedSize * 2 || any { it !in '0'..'9' && it !in 'a'..'f' }) return null
    return ByteArray(expectedSize) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
