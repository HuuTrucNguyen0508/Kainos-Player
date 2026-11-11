package com.universalmusic.player.platform

internal fun <T : Any> metadataBatchResults(
    results: List<Result<T?>>,
    mustPropagate: (Throwable) -> Boolean,
): List<T> {
    results.firstNotNullOfOrNull { it.exceptionOrNull()?.takeIf(mustPropagate) }?.let { throw it }
    val tracks = results.mapNotNull { it.getOrNull() }
    if (tracks.isEmpty()) results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
    return tracks
}
