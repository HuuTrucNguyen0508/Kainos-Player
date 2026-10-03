package com.universalmusic.player.platform

import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.Artwork
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.Playlist
import com.universalmusic.player.domain.model.ProviderEntityRef
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.Track
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class LibrespotDiscoverOut(
    val ok: Boolean = false,
    val id: String? = null,
    val name: String? = null,
    val description: String? = null,
    val artwork_url: String? = null,
    val tracks: List<LibrespotDiscoverTrack> = emptyList(),
    val error: String? = null,
)

@Serializable
private data class LibrespotDiscoverTrack(
    val id: String,
    val title: String,
    val artists: List<String> = emptyList(),
    val duration_ms: Int = 0,
    val artwork_url: String? = null,
)

private val discoverJson = Json { ignoreUnknownKeys = true }

actual suspend fun fetchLibrespotDiscoverWeekly(playlistId: String?): Playlist? = withContext(Dispatchers.IO) {
    val credentials = LibrespotPaths.defaults().credentialsFile
    if (!credentials.exists()) return@withContext null
    val binary = findDiscoverWeeklyBinary() ?: return@withContext null
    val command = buildList {
        add(binary.toAbsolutePath().toString())
        add(credentials.toAbsolutePath().toString())
        playlistId?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }
    val process = ProcessBuilder(command)
        .redirectError(ProcessBuilder.Redirect.PIPE)
        .start()
    val finished = process.waitFor(90, TimeUnit.SECONDS)
    if (!finished) {
        process.destroyForcibly()
        return@withContext null
    }
    val stdout = process.inputStream.readBytes().toString(StandardCharsets.UTF_8).trim()
    if (stdout.isEmpty()) return@withContext null
    val parsed = runCatching { discoverJson.decodeFromString<LibrespotDiscoverOut>(stdout) }.getOrNull()
        ?: return@withContext null
    if (!parsed.ok || parsed.id.isNullOrBlank()) return@withContext null
    parsed.toPlaylist()
}

private fun LibrespotDiscoverOut.toPlaylist(): Playlist {
    val playlistId = id!!
    val tracks = tracks.mapNotNull { row ->
        if (row.id.isBlank() || row.title.isBlank()) return@mapNotNull null
        Track(
            canonicalId = "spotify:${row.id}",
            title = row.title,
            artists = row.artists
                .filter { it.isNotBlank() }
                .map { ArtistRef("spotify-artist:${it.lowercase()}", it) },
            durationMs = row.duration_ms.takeIf { it > 0 }?.toLong(),
            artwork = row.artwork_url?.let { Artwork(it) },
            sources = listOf(
                PlaybackSource(
                    provider = ProviderId.SPOTIFY,
                    providerTrackId = row.id,
                    quality = AudioQuality(tier = QualityTier.LOSSLESS, sampleRateHz = 44_100, bitDepth = 16),
                    isPlayable = true,
                    handle = PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, row.id, durationMs = row.duration_ms.takeIf { it > 0 }?.toLong()),
                ),
            ),
        )
    }
    return Playlist(
        canonicalId = "spotify-playlist:$playlistId",
        title = name?.takeIf { it.isNotBlank() } ?: "Discover Weekly",
        description = description,
        artwork = artwork_url?.let { Artwork(it) },
        ownerName = "Spotify",
        trackCount = tracks.size,
        tracks = tracks,
        source = ProviderEntityRef(ProviderId.SPOTIFY, playlistId),
    )
}

private fun findDiscoverWeeklyBinary(): Path? {
    val cwd = Path.of("").toAbsolutePath()
    val home = Path.of(System.getProperty("user.home", "."))
    val candidates = buildList {
        System.getenv("KAINOS_DISCOVER_WEEKLY")?.takeIf { it.isNotBlank() }?.let { add(Path.of(it)) }
        // Gradle :desktopApp:run uses desktopApp/ as cwd; repo tools/ lives one level up.
        add(cwd.resolve("tools/librespot-runtime/bin/kainos-discover-weekly"))
        add(cwd.parent.resolve("tools/librespot-runtime/bin/kainos-discover-weekly"))
        add(home.resolve(".local/bin/kainos-discover-weekly"))
        add(home.resolve(".universal-music-player/bin/kainos-discover-weekly"))
        System.getenv("PATH").orEmpty()
            .split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .forEach { add(Path.of(it).resolve("kainos-discover-weekly")) }
    }
    return candidates
        .asSequence()
        .map(Path::normalize)
        .distinct()
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}
