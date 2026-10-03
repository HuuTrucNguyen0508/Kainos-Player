package com.universalmusic.player.domain.continuation

import com.universalmusic.player.domain.model.Track
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Outcome of a one-shot Search autoplay continuation attempt. */
sealed class ContinuationOutcome {
    data class Appended(val tracks: List<Track>) : ContinuationOutcome()
    data object Disabled : ContinuationOutcome()
    data object NotSearchSession : ContinuationOutcome()
    data object AlreadyAttempted : ContinuationOutcome()
    data object NothingEligible : ContinuationOutcome()
    data class Unavailable(val reason: String) : ContinuationOutcome()
}

/**
 * One-shot Search continuation. Marks attempted before fetching to avoid retry loops.
 * Manual queue items are never reordered; callers must append at the tail only.
 */
class SearchAutoplayController(
    private val autoplayEnabled: () -> Boolean,
    private val fetchContinuation: suspend (
        seed: Track,
        query: String,
        excludeCanonicalIds: Set<String>,
    ) -> List<Track>,
    private val batchLimit: Int = 12,
) {
    private val mutex = Mutex()
    private var generation: Long = 0L
    private var session: SearchSession? = null
    private var lastOutcome: ContinuationOutcome? = null

    val lastContinuationOutcome: ContinuationOutcome? get() = lastOutcome

    fun beginSearchPlayback(tracks: List<Track>, query: String) {
        generation++
        session = SearchSession(
            generation = generation,
            query = query.trim(),
            seeds = tracks,
            attempted = false,
        )
        lastOutcome = null
    }

    fun clearSearchSession() {
        generation++
        session = null
    }

    suspend fun requestContinuation(excludeCanonicalIds: Set<String>): ContinuationOutcome = mutex.withLock {
        if (!autoplayEnabled()) {
            return ContinuationOutcome.Disabled.also { lastOutcome = it }
        }
        val active = session ?: return ContinuationOutcome.NotSearchSession.also { lastOutcome = it }
        if (active.attempted) {
            return ContinuationOutcome.AlreadyAttempted.also { lastOutcome = it }
        }
        active.attempted = true
        val gen = active.generation
        val seed = active.seeds.lastOrNull { it.canonicalId !in excludeCanonicalIds }
            ?: active.seeds.lastOrNull()
            ?: return ContinuationOutcome.NothingEligible.also { lastOutcome = it }

        val fetched = runCatching {
            fetchContinuation(
                seed,
                active.query,
                excludeCanonicalIds + active.seeds.map { it.canonicalId },
            ).distinctBy { it.canonicalId }.take(batchLimit)
        }.getOrElse { error ->
            return ContinuationOutcome.Unavailable(error.message ?: "Continuation failed")
                .also { lastOutcome = it }
        }
        if (gen != generation) {
            return ContinuationOutcome.NothingEligible.also { lastOutcome = it }
        }
        val outcome = if (fetched.isEmpty()) {
            ContinuationOutcome.NothingEligible
        } else {
            ContinuationOutcome.Appended(fetched)
        }
        lastOutcome = outcome
        return outcome
    }

    private data class SearchSession(
        val generation: Long,
        val query: String,
        val seeds: List<Track>,
        var attempted: Boolean,
    )
}
