package com.universalmusic.player.domain.playback

/**
 * A provider refused a request because of its rate limit, and asked for longer than a start may
 * wait. Every retry restarts the limit, so playback pauses on this instead of moving to the next
 * track (which would hit the same API again).
 */
class ProviderRateLimitedException(
    message: String,
    /** The provider's Retry-After, or null when it gave none. */
    val retryAfterMs: Long?,
) : IllegalStateException(message)

/** The rate limit behind [this] failure, looking through wrappers. */
fun Throwable.rateLimitCause(): ProviderRateLimitedException? =
    generateSequence(this) { it.cause.takeIf { cause -> cause !== it } }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<ProviderRateLimitedException>()
        .firstOrNull()

private const val MAX_CAUSE_DEPTH = 8
