package com.universalmusic.player.data.local

/** Optional disk snapshot of the last successful local scan. */
interface LocalLibraryScanCache {
    suspend fun read(configKey: String): List<LocalTrack>?
    suspend fun write(configKey: String, tracks: List<LocalTrack>)
}

fun LocalLibraryScanConfig.cacheKey(): String {
    val folders = folders.map(String::trim).filter(String::isNotEmpty).sorted().joinToString("|")
    return "mode=$mode;mediaStore=$includeMediaStore;folders=$folders"
}
