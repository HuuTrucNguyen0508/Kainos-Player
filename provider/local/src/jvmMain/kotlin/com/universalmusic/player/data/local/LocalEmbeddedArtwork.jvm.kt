package com.universalmusic.player.data.local

/** Desktop scans already extract embedded art via ffprobe (see LocalAudioProbe). */
actual fun createLocalEmbeddedArtworkExtractor(): LocalEmbeddedArtworkExtractor? = null
