package com.universalmusic.player.data.local

/**
 * Merges multiple local sources. First occurrence of a dedupe key wins
 * (prefer listing SAF/roots before MediaStore so folder picks take priority).
 */
class CompositeLocalTrackSource(
    private val sources: List<LocalTrackSource>,
) : LocalTrackSource {
    constructor(vararg sources: LocalTrackSource) : this(sources.toList())

    override suspend fun scan(): List<LocalTrack> {
        val merged = linkedMapOf<String, LocalTrack>()
        for (source in sources) {
            for (track in source.scan()) {
                merged.putIfAbsent(track.dedupeKey(), track)
            }
        }
        return merged.values.toList()
    }
}

internal fun LocalTrack.dedupeKey(): String {
    val length = contentLength?.takeIf { it > 0 }
    val duration = durationMs?.takeIf { it > 0 }
    if (length != null || duration != null) {
        return "${title.trim().lowercase()}|${artists.joinToString(",") { it.trim().lowercase() }}|" +
            "${album.orEmpty().trim().lowercase()}|${duration ?: "-"}|${length ?: "-"}"
    }
    return location
}
