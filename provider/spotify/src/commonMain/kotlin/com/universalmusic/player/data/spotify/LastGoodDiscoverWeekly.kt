package com.universalmusic.player.data.spotify

import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.data.library.toDomain
import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.platform.PlaybackTrace
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One small text file in the metadata cache directory. Blocking; [LastGoodDiscoverWeekly] calls it off the main thread. */
interface SnapshotFile {
    fun read(): String?
    fun write(text: String)
    fun delete()
}

@Serializable
internal data class DiscoverWeeklySnapshot(
    val version: Int = 1,
    val fetchedAtMs: Long,
    /** Discover Weekly is per account; another account's list is never shown. */
    val spotifyAccountId: String? = null,
    val playlistId: String,
    val title: String,
    val description: String? = null,
    val artworkUrl: String? = null,
    val tracks: List<PersistedTrack>,
)

/**
 * The last Discover Weekly that came back with tracks, kept in memory and in one JSON file, so the
 * Home shelf survives a failed refresh (and shows at once after a restart) instead of disappearing.
 * A list served from here carries its age in its title, because Discover Weekly changes weekly.
 */
class LastGoodDiscoverWeekly(
    private val file: SnapshotFile?,
    private val clock: () -> Long,
) {
    private val mutex = Mutex()
    @Volatile private var snapshot: DiscoverWeeklySnapshot? = null
    private var loaded = false

    /** Records [playlist] as the last good list when it has tracks. Returns it unchanged. */
    suspend fun remember(playlist: Playlist, spotifyAccountId: String?): Playlist {
        if (playlist.tracks.isEmpty()) return playlist
        val now = clock()
        val next = DiscoverWeeklySnapshot(
            fetchedAtMs = now,
            spotifyAccountId = spotifyAccountId,
            playlistId = playlist.source.providerEntityId,
            title = playlist.title,
            description = playlist.description,
            artworkUrl = playlist.artwork?.url,
            tracks = playlist.tracks.map { it.toPersisted(now) },
        )
        mutex.withLock {
            loaded = true
            snapshot = next
            file?.let { target -> io("save") { target.write(json.encodeToString(DiscoverWeeklySnapshot.serializer(), next)) } }
        }
        return playlist
    }

    /** The last good list for [spotifyAccountId] (any, when it is not known yet), titled with its age; null when there is none. */
    suspend fun fallback(spotifyAccountId: String?): Playlist? {
        val saved = mutex.withLock {
            if (!loaded) {
                loaded = true
                snapshot = file?.let { source -> io("read") { source.read() } }?.let(::decode)
            }
            snapshot
        } ?: return null
        if (spotifyAccountId != null && saved.spotifyAccountId != null && saved.spotifyAccountId != spotifyAccountId) return null
        return saved.toPlaylist(clock())
    }

    /** Forget the list, e.g. when Spotify is disconnected. */
    suspend fun clear() = mutex.withLock {
        loaded = true
        snapshot = null
        file?.let { target -> io("delete") { target.delete() } }
    }

    private fun decode(text: String): DiscoverWeeklySnapshot? =
        runCatching { json.decodeFromString(DiscoverWeeklySnapshot.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.version == 1 && it.tracks.isNotEmpty() }

    private suspend fun <T> io(action: String, block: () -> T): T? = try {
        withContext(Dispatchers.IO) { block() }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        PlaybackTrace.log("Spotify", "Discover Weekly cache $action failed: $failure")
        null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

private fun DiscoverWeeklySnapshot.toPlaylist(nowMs: Long): Playlist {
    val tracks = tracks.map { it.toDomain() }
    return Playlist(
        canonicalId = "spotify-playlist:$playlistId",
        title = "$title · ${savedAgo(nowMs - fetchedAtMs)}",
        description = description,
        artwork = artworkUrl?.let(::Artwork),
        ownerName = "Spotify",
        trackCount = tracks.size,
        tracks = tracks,
        source = ProviderEntityRef(ProviderId.SPOTIFY, playlistId),
    )
}

/** Relative, so it needs no time zone: "saved 3 days ago". */
internal fun savedAgo(ageMs: Long): String {
    val hours = ageMs.coerceAtLeast(0) / HOUR_MS
    return when {
        hours < 1 -> "saved just now"
        hours < 24 -> if (hours == 1L) "saved 1 hour ago" else "saved $hours hours ago"
        hours < 48 -> "saved yesterday"
        else -> "saved ${hours / 24} days ago"
    }
}

private const val HOUR_MS = 3_600_000L
