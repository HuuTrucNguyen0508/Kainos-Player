package com.universalmusic.player.domain.continuation

import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.platform.PlaybackTrace
import kotlinx.coroutines.CancellationException

/** Providers that recommend their own tracks for a seed (Spotify radio, YouTube Mix). */
private val RADIO_PROVIDERS = setOf(ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC)

/** The radio-capable provider a track came from, or null for local and sample tracks. */
fun Track.radioProvider(): ProviderId? = sources.firstOrNull { it.provider in RADIO_PROVIDERS }?.provider

/**
 * Keeps a queue going with [provider]'s own recommendations, seeded by the track playing when the
 * queue runs low. Never mixes providers: anything [fetch] returns from elsewhere is dropped, and
 * a failed or empty fetch finishes the radio instead of falling back to another source.
 */
class SourceRadio(
    val provider: ProviderId,
    private val fetch: suspend (seed: Track, exclude: Set<String>) -> List<Track>,
    override val lowWater: Int = 2,
    private val batchLimit: Int = 25,
    private val trace: (String) -> Unit = { PlaybackTrace.log("Radio", it) },
) : QueueContinuation {
    /** Everything this radio has queued or seen queued, so tracks the user removed do not come back. */
    private val seen = mutableSetOf<String>()

    override suspend fun nextBatch(queue: PlaybackQueue): List<Track> {
        queue.items.mapTo(seen) { it.track.canonicalId }
        val seed = seedFrom(queue) ?: run {
            trace("$provider radio stopped: no $provider track in the queue to seed from")
            return emptyList()
        }
        val fetched = try {
            fetch(seed, seen.toSet())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            trace("$provider radio unavailable for ${seed.canonicalId}: ${error.message ?: error::class.simpleName}")
            return emptyList()
        }
        val batch = fetched.asSequence()
            .filter { it.radioProvider() == provider }
            .filter { seen.add(it.canonicalId) }
            .take(batchLimit)
            .toList()
        trace(
            if (batch.isEmpty()) {
                "$provider radio stopped: nothing new for ${seed.canonicalId} (fetched ${fetched.size})"
            } else {
                "$provider radio appends ${batch.size} seeded by ${seed.canonicalId}"
            },
        )
        return batch
    }

    /** The current track when it is ours, else the latest of ours already played, else the first queued. */
    private fun seedFrom(queue: PlaybackQueue): Track? =
        (listOfNotNull(queue.current) + queue.history.asReversed() + queue.items)
            .firstOrNull { it.track.radioProvider() == provider }
            ?.track
}
