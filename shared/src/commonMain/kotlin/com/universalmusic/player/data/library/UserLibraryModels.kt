package com.universalmusic.player.data.library

import com.universalmusic.player.domain.model.AlbumRef
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.Track
import kotlinx.serialization.Serializable

const val USER_LIBRARY_FORMAT_VERSION = 1

/**
 * Versioned on-disk user library. Stores provider identities and display metadata only —
 * never resolved streaming audio URLs.
 */
@Serializable
data class UserLibrarySnapshot(
    val version: Int = USER_LIBRARY_FORMAT_VERSION,
    /** Spotify account that owns Spotify-scoped entries; null when disconnected. */
    val spotifyAccountId: String? = null,
    /** App favorites (canonicalIds). Distinct from Spotify Liked Songs. */
    val favoriteIds: List<String> = emptyList(),
    val remembered: List<PersistedTrack> = emptyList(),
    val recents: List<PersistedTrack> = emptyList(),
)

@Serializable
data class PersistedTrack(
    val canonicalId: String,
    val title: String,
    val artists: List<PersistedArtist> = emptyList(),
    val albumCanonicalId: String? = null,
    val albumTitle: String? = null,
    val durationMs: Long? = null,
    /** Remote or local file URI for artwork (metadata only). */
    val artworkUrl: String? = null,
    val explicit: Boolean = false,
    val isrc: String? = null,
    val sources: List<PersistedSource> = emptyList(),
    val cachedAtMs: Long = 0,
)

@Serializable
data class PersistedArtist(
    val canonicalId: String,
    val name: String,
)

@Serializable
data class PersistedSource(
    val provider: String,
    val providerTrackId: String,
    /**
     * Stable local path/content URI for [ProviderId.LOCAL] only.
     * Never a temporary YouTube/Spotify stream URL.
     */
    val localLocation: String? = null,
    val qualityTier: String? = null,
    val qualityCodec: String? = null,
    val qualityBitrateKbps: Int? = null,
    val qualitySampleRateHz: Int? = null,
    val qualityBitDepth: Int? = null,
)

interface UserLibraryStore {
    suspend fun read(): UserLibrarySnapshot
    suspend fun write(snapshot: UserLibrarySnapshot)
}

fun Track.toPersisted(nowMs: Long): PersistedTrack = PersistedTrack(
    canonicalId = canonicalId,
    title = title,
    artists = artists.map { PersistedArtist(it.canonicalId, it.name) },
    albumCanonicalId = album?.canonicalId,
    albumTitle = album?.title,
    durationMs = durationMs,
    artworkUrl = artwork?.url,
    explicit = explicit,
    isrc = isrc,
    sources = sources.map { it.toPersisted() },
    cachedAtMs = nowMs,
)

fun PersistedTrack.toDomain(): Track = Track(
    canonicalId = canonicalId,
    title = title,
    artists = artists.map { ArtistRef(canonicalId = it.canonicalId, name = it.name) },
    album = albumTitle?.let { title ->
        AlbumRef(
            canonicalId = albumCanonicalId ?: "album:$title",
            title = title,
            artwork = artworkUrl?.let(::Artwork),
        )
    },
    durationMs = durationMs,
    artwork = artworkUrl?.let(::Artwork),
    explicit = explicit,
    isrc = isrc,
    sources = sources.mapNotNull { it.toDomain(durationMs) },
)

private fun PlaybackSource.toPersisted(): PersistedSource {
    val localLocation = when (provider) {
        ProviderId.LOCAL -> streamUrl
            ?: (handle as? PlaybackHandle.Url)?.url
        else -> null
    }
    return PersistedSource(
        provider = provider.name,
        providerTrackId = providerTrackId,
        localLocation = localLocation,
        qualityTier = quality?.tier?.name,
        qualityCodec = quality?.codec,
        qualityBitrateKbps = quality?.bitrateKbps,
        qualitySampleRateHz = quality?.sampleRateHz,
        qualityBitDepth = quality?.bitDepth,
    )
}

private fun PersistedSource.toDomain(durationMs: Long?): PlaybackSource? {
    val provider = runCatching { ProviderId.valueOf(provider) }.getOrNull() ?: return null
    val quality = qualityTier?.let { tierName ->
        val tier = runCatching { QualityTier.valueOf(tierName) }.getOrNull() ?: return@let null
        AudioQuality(
            tier = tier,
            codec = qualityCodec,
            bitrateKbps = qualityBitrateKbps,
            sampleRateHz = qualitySampleRateHz,
            bitDepth = qualityBitDepth,
        )
    }
    return when (provider) {
        ProviderId.LOCAL -> {
            val location = localLocation?.takeIf { it.isNotBlank() } ?: return null
            PlaybackSource(
                provider = provider,
                providerTrackId = providerTrackId,
                streamUrl = location,
                quality = quality,
                isPlayable = true,
                handle = PlaybackHandle.Url(location),
            )
        }
        ProviderId.SPOTIFY, ProviderId.YOUTUBE_MUSIC, ProviderId.SAMPLE -> PlaybackSource(
            provider = provider,
            providerTrackId = providerTrackId,
            streamUrl = null,
            quality = quality,
            isPlayable = true,
            handle = PlaybackHandle.ProviderPlayback(
                provider = provider,
                trackId = providerTrackId,
                durationMs = durationMs,
            ),
        )
    }
}

/** True when this track can play without network (local file identity present). */
fun Track.isLocallyPlayable(): Boolean =
    sources.any { it.provider == ProviderId.LOCAL && it.handle is PlaybackHandle.Url }

fun Track.requiresNetworkToPlay(): Boolean = !isLocallyPlayable()

fun UserLibrarySnapshot.scopedToSpotifyAccount(activeAccountId: String?): UserLibrarySnapshot {
    if (spotifyAccountId == null && activeAccountId == null) return this
    if (spotifyAccountId == activeAccountId) return copy(spotifyAccountId = activeAccountId)
    // Account switch or disconnect: drop Spotify-scoped app data; keep local/YouTube/sample.
    fun isSpotifyCanonical(id: String) = id.startsWith("spotify:")
    fun PersistedTrack.isSpotifyOnly(): Boolean {
        if (isSpotifyCanonical(canonicalId)) return true
        val providers = sources.map { it.provider }.toSet()
        return ProviderId.SPOTIFY.name in providers &&
            ProviderId.LOCAL.name !in providers &&
            ProviderId.YOUTUBE_MUSIC.name !in providers
    }

    return UserLibrarySnapshot(
        version = version.coerceAtLeast(USER_LIBRARY_FORMAT_VERSION),
        spotifyAccountId = activeAccountId,
        favoriteIds = favoriteIds.filterNot(::isSpotifyCanonical),
        remembered = remembered.filterNot { it.isSpotifyOnly() },
        recents = recents.filterNot { it.isSpotifyOnly() },
    )
}

fun UserLibrarySnapshot.migrated(): UserLibrarySnapshot {
    if (version >= USER_LIBRARY_FORMAT_VERSION) return this
    return copy(version = USER_LIBRARY_FORMAT_VERSION)
}
