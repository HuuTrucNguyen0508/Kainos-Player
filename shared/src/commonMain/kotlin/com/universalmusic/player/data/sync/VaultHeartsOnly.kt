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
    return library.savedTracks.value.asSequence()
        .filter { it.canonicalId in favorites }
        .mapNotNull { track ->
            val source = track.sourceFor(ProviderId.LOCAL) ?: return@mapNotNull null
            if (source.providerTrackId.startsWith(HEARTED_AUDIO_CACHE_PROVIDER_PREFIX)) {
                return@mapNotNull null
            }
            val url = (source.handle as? PlaybackHandle.Url)?.url ?: return@mapNotNull null
            vaultAudioBasename(url)
        }
        .toSet()
}

fun vaultAudioBasename(location: String): String? {
    val raw = location.substringAfterLast('/')
        .substringBefore('?')
        .substringBefore('#')
        .trim()
    if (raw.isBlank()) return null
    val decoded = decodeLight(raw)
    return decoded.lowercase().takeIf { it.isNotBlank() }
}

/** Keep tombstones; drop live entries whose basename is not in [allowedBasenamesLower]. */
fun VaultIndexDocument.filterEntriesByBasenames(allowedBasenamesLower: Set<String>): VaultIndexDocument {
    if (allowedBasenamesLower.isEmpty()) {
        return copy(entries = emptyList())
    }
    return copy(
        entries = entries.filter { entry ->
            val base = entry.relPath.substringAfterLast('/').lowercase()
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

private fun decodeLight(value: String): String {
    if ('%' !in value && '+' !in value) return value
    return buildString(value.length) {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '+' -> {
                    append(' ')
                    i += 1
                }
                c == '%' && i + 2 < value.length -> {
                    val hex = value.substring(i + 1, i + 3)
                    val code = hex.toIntOrNull(16)
                    if (code != null) {
                        append(code.toChar())
                        i += 3
                    } else {
                        append(c)
                        i += 1
                    }
                }
                else -> {
                    append(c)
                    i += 1
                }
            }
        }
    }
}
