package com.universalmusic.player.data.sync

import kotlin.random.Random
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Leases bound even a request stuck in transit to a receiver-clock deadline before press + 4s. */
internal class RemoteDirectIntentReceiver(private val clock: TimeSource = TimeSource.Monotonic) {
    private data class Lease(val peer: String, val deadline: TimeMark)
    private val lock = Mutex()
    private val epoch = Random.nextLong().toULong().toString(36) + Random.nextLong().toULong().toString(36)
    private var nextLease = 0L
    private val leases = LinkedHashMap<String, Lease>()
    private val newest = mutableMapOf<Pair<String, String>, Long>()

    suspend fun lease(peer: String): String = lock.withLock {
        leases.entries.removeAll { it.value.deadline.hasPassedNow() }
        val token = "$epoch-${++nextLease}"
        leases[token] = Lease(peer, clock.markNow() + REMOTE_COMMAND_DEADLINE_MS.milliseconds)
        // Capacity eviction fails closed. No old token ever becomes valid again.
        while (leases.size > 256) leases.remove(leases.keys.first())
        token
    }

    suspend fun apply(peer: String, intent: RemoteDirectIntent, player: RemotePlayer): RemoteCommandReply = lock.withLock {
        val lease = intent.lease?.let { token -> leases[token]?.takeIf { it.peer == peer } }
        val key = peer to intent.senderId
        val previous = newest[key] ?: 0L
        val leaseInvalid = intent.lease != null && (lease == null || lease.deadline.hasPassedNow())
        if (leaseInvalid || intent.ageMs !in 0 until REMOTE_COMMAND_DEADLINE_MS ||
            (intent.command != RemoteCommand.Play && intent.command != RemoteCommand.Pause)) {
            return@withLock RemoteCommandReply(player.state.value?.status, leaseRejected = true)
        }
        // A retry of an applied intent, or one a newer press replaced: already the right outcome.
        if (intent.seq <= previous) return@withLock RemoteCommandReply(player.state.value?.status)
        // Reserve before suspension. A failed application must never replay after a newer intent.
        newest[key] = intent.seq
        val ageDeadline = clock.markNow() + (REMOTE_COMMAND_DEADLINE_MS - intent.ageMs).milliseconds
        val deadline = if (lease != null && lease.deadline.elapsedNow() > ageDeadline.elapsedNow()) lease.deadline else ageDeadline
        try { player.apply(intent.command, deadline) }
        catch (_: RemoteCommandExpired) { RemoteCommandReply(player.state.value?.status) }
    }
}
