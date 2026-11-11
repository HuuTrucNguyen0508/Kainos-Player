package com.universalmusic.player.data.local

/**
 * Reads tags from inside one audio file. Desktop reads them during the scan (ffprobe); Android
 * cannot afford to open every SAF file while scanning, so it reads them lazily after the scan
 * and on first play, and the scan cache keeps the result.
 */
fun interface LocalTagReader {
    /** Tags of the file at [location]; empty tags when it has none, null when it could not be opened. */
    suspend fun read(location: String): LocalTags?
}

/** Null on platforms whose scan already reads tags. */
expect fun createLocalTagReader(): LocalTagReader?
