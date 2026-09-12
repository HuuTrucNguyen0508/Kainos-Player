package com.universalmusic.player.data.local

/**
 * Cover art embedded in the audio file itself (FLAC PICTURE, ID3 APIC, MP4 covr). Step 1 of
 * [LocalArtworkPolicy]. Desktop resolves this during the scan (ffprobe); Android cannot afford
 * to open every file while scanning, so it extracts lazily and caches the result on disk.
 */
interface LocalEmbeddedArtworkExtractor {
    /** Already extracted cover for [trackId], as a URI Coil and the media session can load. Cheap. */
    fun cachedArtworkUri(trackId: String): String?

    /**
     * Opens the file at [location] and pulls the embedded picture. Returns the cached or freshly
     * extracted URI, or null when the file has no picture (that outcome is remembered too, so
     * the file is not reopened on every launch).
     */
    suspend fun extractArtworkUri(trackId: String, location: String): String?
}

/** Null on platforms whose scan already fills embedded artwork. */
expect fun createLocalEmbeddedArtworkExtractor(): LocalEmbeddedArtworkExtractor?
