package com.universalmusic.player.data.sync

import kotlinx.serialization.Serializable

/** Hearted YouTube audio caches; hubs from before cached-audio sync answer 404 and the client skips it. */
const val HOME_LAN_SYNC_PATH_V3_CACHED_AUDIO = "/kainos-sync/v3/cached-audio"
const val HOME_LAN_SYNC_PATH_V3_CACHED_AUDIO_BLOB = "/kainos-sync/v3/cached-audio/blob"
const val HOME_LAN_SYNC_PATH_V3_CACHED_AUDIO_HASH = "/kainos-sync/v3/cached-audio/hash"

/**
 * One downloaded YouTube file in a device's hearted audio cache that a peer may copy.
 * Every offer is a YouTube download: Spotify DRM audio is never cached, so it is never offered.
 */
@Serializable
data class CachedAudioOffer(
    /** The hearted track (`yt:…`, or `spotify:…` for a YouTube match of a Spotify heart). */
    val ownerCanonicalId: String,
    val youtubeVideoId: String,
    /** The cache file's name. Cache files are never rewritten in place, so a new file means a new token. */
    val fileToken: String,
    val sizeBytes: Long,
    val title: String = "",
    val artistLine: String = "",
    val qualityTier: String? = null,
    val qualityCodec: String? = null,
    val qualityBitrateKbps: Int? = null,
    val qualitySampleRateHz: Int? = null,
    val spotifyViaYouTubeMatch: Boolean = false,
)

/** What one side of Sync now holds: its offers, and the Spotify account that scopes Spotify matches. */
@Serializable
data class CachedAudioSyncDocument(
    val deviceId: String,
    val spotifyAccountId: String? = null,
    val offers: List<CachedAudioOffer> = emptyList(),
    /** Owners already in this device's cache, offered or not, so the peer does not send them again. */
    val heldOwners: List<String> = emptyList(),
)

/** A cached file waiting for Transfer or Skip, like a [PendingVaultTransfer]. */
@Serializable
data class PendingCachedAudioTransfer(
    val offer: CachedAudioOffer,
    val direction: VaultCopyDirection,
) {
    val label: String
        get() {
            val name = listOf(offer.title, offer.artistLine).filter { it.isNotBlank() }.joinToString(" · ")
                .ifBlank { offer.ownerCanonicalId }
            return when (direction) {
                VaultCopyDirection.TO_LOCAL -> "Download offline copy: $name"
                VaultCopyDirection.TO_REMOTE -> "Upload offline copy: $name"
            }
        }
}

/**
 * A Spotify match crosses only between two devices on the same known Spotify account (as hearts do);
 * a YouTube heart's audio always may.
 */
fun cachedAudioMayCross(ownerCanonicalId: String, localSpotifyAccountId: String?, peerSpotifyAccountId: String?): Boolean =
    when {
        ownerCanonicalId.startsWith("yt:") -> true
        isSpotifyHeartCanonicalId(ownerCanonicalId) ->
            localSpotifyAccountId != null && localSpotifyAccountId == peerSpotifyAccountId
        else -> false
    }

/** Cache file names this app writes: a 32-hex base and a short audio extension. */
private val CACHE_FILE_TOKEN = Regex("[0-9a-f]{32}\\.[a-z0-9]{1,5}")

fun isCachedAudioFileToken(token: String): Boolean = CACHE_FILE_TOKEN.matches(token)
