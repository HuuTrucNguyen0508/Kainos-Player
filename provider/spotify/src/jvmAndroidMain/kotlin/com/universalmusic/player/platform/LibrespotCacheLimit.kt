package com.universalmusic.player.platform

import java.io.File
import java.io.IOException

internal const val LIBRESPOT_CACHE_LIMIT_BYTES = 1024L * 1024 * 1024

/** Called before Session opens its cache journal, with no receiver using the directory. */
internal fun resetOversizedLibrespotCache(
    directory: File,
    limitBytes: Long = LIBRESPOT_CACHE_LIMIT_BYTES,
): Long? {
    fun size(file: File): Long = when {
        !file.exists() -> 0L
        file.isDirectory -> (file.listFiles() ?: throw IOException("Cannot list $file")).sumOf(::size)
        else -> file.length()
    }
    val bytes = size(directory)
    if (bytes <= limitBytes) return null
    if (!directory.deleteRecursively()) throw IOException("Cannot reset librespot cache $directory")
    return bytes
}
