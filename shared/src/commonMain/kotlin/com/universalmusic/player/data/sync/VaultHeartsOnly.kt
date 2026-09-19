package com.universalmusic.player.data.sync

import com.universalmusic.player.data.cache.HEARTED_AUDIO_CACHE_PROVIDER_PREFIX
import com.universalmusic.player.data.library.LibraryRepository
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.ProviderId

/**
 * Basenames of app-hearted tracks that have a real local library file (not hearted-audio-cache).
 * Used to limit vault blob sync to favorites only.
 */
fun heartedLocalAudioFileNames(library: LibraryRepository): Set<String> {
    val favorites = library.favoriteIds.value
    if (favorites.isEmpty()) return emptySet()
    val fromFiles = library.savedTracks.value.asSequence()
        .filter { it.canonicalId in favorites }
        .mapNotNull { track ->
            if (isLocalFileHeartCanonicalId(track.canonicalId)) {
                return@mapNotNull normalizedLocalFileHeartBasename(track.canonicalId)
            }
            val source = track.sourceFor(ProviderId.LOCAL) ?: return@mapNotNull null
            if (source.providerTrackId.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX)) {
                return@mapNotNull null
            }
            val url = (source.handle as? PlaybackHandle.Url)?.url ?: return@mapNotNull null
            vaultAudioBasename(url)
        }
    val fromIds = favorites.asSequence()
        .mapNotNull { id -> normalizedLocalFileHeartBasename(id) }
    return (fromFiles + fromIds).toSet()
}

fun vaultAudioBasename(location: String): String? = basenameFromLocalLocation(location)

/** Keep tombstones; drop live entries whose basename is not in [allowedBasenamesLower]. */
fun VaultIndexDocument.filterEntriesByBasenames(allowedBasenamesLower: Set<String>): VaultIndexDocument {
    if (allowedBasenamesLower.isEmpty()) {
        return copy(entries = emptyList())
    }
    return copy(
        entries = entries.filter { entry ->
            val base = basenameFromLocalLocation(entry.relPath) ?: entry.relPath.substringAfterLast('/').lowercase()
            base in allowedBasenamesLower
        },
    )
}

fun VaultIndexDocument.applyHeartsOnlyVaultFilter(
    heartsOnly: Boolean,
    heartedBasenamesLower: Set<String>,
): VaultIndexDocument {
    if (!heartsOnly) return this
    return filterEntriesByBasenames(heartedBasenamesLower)
}
