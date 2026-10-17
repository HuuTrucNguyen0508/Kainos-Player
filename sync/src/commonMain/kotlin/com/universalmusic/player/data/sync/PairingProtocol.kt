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
private const val PAIRING_OUTCOME_TTL_MS = PAIRING_ATTEMPT_TTL_MS + 60_000L
private const val MAX_PAIRING_OUTCOMES = PAIRING_STARTS_PER_MINUTE * 3 + 1

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
    private val isAvailable: () -> Boolean = { true },
) {
    private sealed interface Phase {
        data object AwaitingReveal : Phase
        data class Revealed(val code: PairingCode, val pcConfirmed: Boolean, val phoneConfirmed: Boolean) : Phase
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

    private data class AttemptKey(val id: String, val client: CertFingerprint)
    private data class TerminalOutcome(val status: PairStatusResponse, val finishedAtMs: Long)

    private val mutex = Mutex()
    private var activeAttempt: Attempt? = null
    private val outcomes = linkedMapOf<AttemptKey, TerminalOutcome>()
    private val recentStarts = ArrayDeque<Long>()
    private val _incoming = MutableStateFlow<PairingAttempt?>(null)
    val incoming: StateFlow<PairingAttempt?> = _incoming.asStateFlow()

    suspend fun start(client: CertFingerprint, request: PairStartRequest): PairingReply<PairStartResponse> =
        locked {
            if (!isAvailable()) return@locked refused(PairingRefusal.ENDED, "Identity reset is in progress")
            if (activeAttempt != null) return@locked refused(PairingRefusal.BUSY, "Another pairing is in progress")
            val now = clock()
            while (recentStarts.firstOrNull()?.let { now - it >= 60_000L } == true) recentStarts.removeFirst()
            if (recentStarts.size >= PAIRING_STARTS_PER_MINUTE) {
                return@locked refused(PairingRefusal.RATE_LIMITED, "Too many pairing attempts; wait a minute")
            }
            val commit = request.commitHex.hexBytes(32)
            if (commit == null || request.deviceId.isBlank() || request.deviceId.length > 128 ||
                request.displayName.length > 64
            ) {
                return@locked refused(PairingRefusal.MALFORMED, "Malformed pairing start")
            }
            recentStarts.addLast(now)
            val attempt = Attempt(
                id = random(16).toHex(),
                client = client,
                deviceId = request.deviceId,
                name = request.displayName.ifBlank { "Unnamed device" },
                commit = commit,
                hubNonce = random(PAIRING_NONCE_BYTES),
                startedAtMs = now,
                phase = Phase.AwaitingReveal,
            )
            activeAttempt = attempt
            publish()
            PairingReply.Ok(PairStartResponse(attempt.id, attempt.hubNonce.toHex()))
        }

    suspend fun reveal(client: CertFingerprint, request: PairRevealRequest): PairingReply<Unit> = locked {
        val attempt = owned(client, request.attemptId) ?: return@locked finishedOrForeign(client, request.attemptId)
        when (attempt.phase) {
            is Phase.Revealed -> return@locked refused(PairingRefusal.MALFORMED, "Nonce already revealed")
            Phase.AwaitingReveal -> Unit
        }
        val nonce = request.nonceHex.hexBytes(PAIRING_NONCE_BYTES)
        if (nonce == null || !constantTimeEquals(pairingCommit(nonce), attempt.commit)) {
            end(attempt, "The phone's pairing check did not match")
            return@locked finishedOrForeign(client, request.attemptId)
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
        val attempt = owned(client, attemptId) ?: return@locked finishedOrForeign(client, attemptId)
        confirm(attempt) { it.copy(phoneConfirmed = true) }
    }

    /** The PC's Confirm button; it names the attempt it showed, so a newer attempt is never confirmed by it. */
    suspend fun confirmFromPc(attemptId: String): PairingReply<Unit> = locked {
        val attempt = activeAttempt?.takeIf { it.id == attemptId } ?: return@locked finishedOrForeign(attemptId)
        confirm(attempt) { it.copy(pcConfirmed = true) }
    }

    suspend fun rejectFromPc(attemptId: String): PairingReply<Unit> = locked {
        val attempt = activeAttempt?.takeIf { it.id == attemptId } ?: return@locked finishedOrForeign(attemptId)
        end(attempt, "The PC rejected the pairing")
        PairingReply.Ok(Unit)
    }

    suspend fun cancel(client: CertFingerprint, attemptId: String): PairingReply<Unit> = locked {
        val attempt = owned(client, attemptId) ?: return@locked finishedOrForeign(client, attemptId)
        end(attempt, "The phone cancelled the pairing")
        PairingReply.Ok(Unit)
    }

    suspend fun status(client: CertFingerprint, attemptId: String): PairingReply<PairStatusResponse> = locked {
        if (owned(client, attemptId) != null) return@locked PairingReply.Ok(PairStatusResponse(PairState.PENDING))
        val outcome = outcomes[AttemptKey(attemptId, client)] ?: return@locked foreign()
        PairingReply.Ok(outcome.status)
    }

    /** Ends a pending attempt that ran out of time; the hub calls this on a timer. */
    suspend fun expireDue() = locked { Unit }

    /** Ends any pending attempt, for example when this PC's identity is reset. */
    suspend fun abandon(reason: String) = locked {
        activeAttempt?.let { end(it, reason) }
        Unit
    }

    private suspend fun <T> locked(block: suspend () -> T): T = mutex.withLock {
        val now = clock()
        outcomes.entries.removeAll { now - it.value.finishedAtMs >= PAIRING_OUTCOME_TTL_MS }
        activeAttempt?.takeIf { now - it.startedAtMs >= PAIRING_ATTEMPT_TTL_MS }
            ?.let { end(it, "The pairing timed out") }
        block()
    }

    private suspend fun confirm(attempt: Attempt, mark: (Phase.Revealed) -> Phase.Revealed): PairingReply<Unit> {
        val phase = attempt.phase
        if (phase !is Phase.Revealed) {
            return refused(PairingRefusal.MALFORMED, "Nothing to confirm yet")
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
            finish(attempt, PairStatusResponse(PairState.PAIRED, hubDeviceId = hubDeviceId()))
        } else {
            attempt.phase = next
        }
        publish()
        return PairingReply.Ok(Unit)
    }

    private fun owned(client: CertFingerprint, attemptId: String): Attempt? =
        activeAttempt?.takeIf { attemptId.hexBytes(16) != null && it.id == attemptId && it.client == client }

    private fun end(attempt: Attempt, reason: String) {
        finish(attempt, PairStatusResponse(PairState.ENDED, reason = reason))
        publish()
    }

    private fun finish(attempt: Attempt, status: PairStatusResponse) {
        outcomes[AttemptKey(attempt.id, attempt.client)] = TerminalOutcome(status, clock())
        while (outcomes.size > MAX_PAIRING_OUTCOMES) outcomes.remove(outcomes.keys.first())
        activeAttempt = null
    }

    private fun publish() {
        val attempt = activeAttempt
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

    private fun foreign() = refused(PairingRefusal.FOREIGN, "Not this device's pairing attempt")

    private fun finishedOrForeign(client: CertFingerprint, attemptId: String): PairingReply.Refused =
        outcomes[AttemptKey(attemptId, client)]?.let(::ended) ?: foreign()

    private fun finishedOrForeign(attemptId: String): PairingReply.Refused =
        outcomes.entries.firstOrNull { it.key.id == attemptId }?.value?.let(::ended) ?: foreign()

    private fun ended(outcome: TerminalOutcome): PairingReply.Refused =
        refused(PairingRefusal.ENDED, outcome.status.reason ?: "The pairing already finished")

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
