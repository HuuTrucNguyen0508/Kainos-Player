package com.universalmusic.player.platform

import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.auth.TokenStore
import com.universalmusic.player.data.cache.DefaultHeartedAudioCache
import com.universalmusic.player.data.cache.DefaultMetadataArtworkCache
import com.universalmusic.player.data.cache.FileHeartedAudioCacheDisk
import com.universalmusic.player.data.cache.FileMetadataCacheDisk
import com.universalmusic.player.data.cache.HeartedAudioCache
import com.universalmusic.player.data.cache.MetadataArtworkCache
import com.universalmusic.player.data.config.AppConfig
import com.universalmusic.player.data.db.DbKainosPlaylistStore
import com.universalmusic.player.data.db.DbLocalLibraryScanCache
import com.universalmusic.player.data.db.DbSessionSnapshotStore
import com.universalmusic.player.data.db.DbUserLibraryStore
import com.universalmusic.player.data.db.JvmLegacyJsonSource
import com.universalmusic.player.data.db.KainosDatabase
import com.universalmusic.player.data.db.KainosStorage
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.data.playlist.KainosPlaylistStore
import com.universalmusic.player.data.session.SessionSnapshotStore
import com.universalmusic.player.data.local.LocalLibraryScanCache
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.domain.model.ProviderId
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.nio.file.Files
import kotlin.io.path.div
import java.nio.file.Path
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties
import kotlin.io.path.exists
import kotlin.io.path.readText

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun configDir(): Path {
    val home = System.getProperty("user.home")
    val dir = Path.of(home, ".universal-music-player")
    Files.createDirectories(dir)
    // Holds the Spotify refresh token and the Home sync hub key; keep it owner-only.
    restrictToOwner(dir, directory = true)
    return dir
}

/** Shared with HomeLanTls.jvm.kt for hub PKCS12 under ~/.universal-music-player/. */

fun homeLanConfigDir(): Path = configDir()

private val kainosStorage by lazy {
    KainosStorage(
        openDriver = {
            JdbcSqliteDriver(
                "jdbc:sqlite:" + (configDir() / "kainos.db"),
                Properties().apply {
                    put("foreign_keys", "true")
                    put("journal_mode", "WAL")
                },
                KainosDatabase.Schema,
            )
        },
        legacy = JvmLegacyJsonSource(configDir()),
        clock = ::currentTimeMillis,
    )
}

actual fun createHttpClient(): HttpClient = HttpClient(CIO) {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000
        connectTimeoutMillis = 10_000
        socketTimeoutMillis = 30_000
    }
    install(ContentNegotiation) { json(json) }
}

actual fun createTokenStore(): TokenStore = FileTokenStore(configDir() / "tokens.json")

actual fun createSettingsStore(): SettingsStore = FileSettingsStore(configDir() / "settings.json")

actual fun createUserLibraryStore(): UserLibraryStore = DbUserLibraryStore(kainosStorage)

actual fun createKainosPlaylistStore(): KainosPlaylistStore = DbKainosPlaylistStore(kainosStorage)

actual fun createSessionSnapshotStore(): SessionSnapshotStore = DbSessionSnapshotStore(kainosStorage)

actual fun createMetadataArtworkCache(): MetadataArtworkCache {
    val disk = FileMetadataCacheDisk(configDir() / "meta-cache")
    return DefaultMetadataArtworkCache(
        disk = disk,
        downloadArtwork = ::downloadArtworkBytes,
    )
}

actual fun createHeartedAudioCache(): HeartedAudioCache =
    DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(configDir() / "audio-cache"))

private fun downloadArtworkBytes(url: String): ByteArray? = runCatching {
    URI(url).toURL().openStream().use { input ->
        input.readBytes()
    }
}.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= 2 * 1024 * 1024 }

actual fun createLocalLibraryScanCache(): LocalLibraryScanCache? = DbLocalLibraryScanCache(kainosStorage)

actual fun loadAppConfig(): AppConfig {
    val env = System.getenv()
    val props = Properties()
    val file = Path.of("secrets.properties")
    if (file.exists()) {
        file.toFile().inputStream().use { props.load(it) }
    }
    val homeFile = configDir() / "secrets.properties"
    if (homeFile.exists()) {
        homeFile.toFile().inputStream().use { props.load(it) }
    }
    fun value(key: String): String? = env[key] ?: props.getProperty(key)?.takeIf { it.isNotBlank() }
    return AppConfig(
        spotifyClientId = value("SPOTIFY_CLIENT_ID"),
        spotifyRedirectUri = value("SPOTIFY_REDIRECT_URI") ?: "http://127.0.0.1:43821/callback",
        youtubeDataApiKey = value("YOUTUBE_DATA_API_KEY"),
    )
}

internal class FileTokenStore(private val path: Path) : TokenStore {
    private val mutex = Mutex()

    init {
        restrictToOwner(path)
    }

    override suspend fun read(provider: ProviderId): AuthTokens? = mutex.withLock {
        readAll()[provider.name]
    }

    override suspend fun write(provider: ProviderId, tokens: AuthTokens) = mutex.withLock {
        val all = readAll().toMutableMap()
        all[provider.name] = tokens
        writeAtomicOwnerOnly(path, json.encodeToString(all))
    }

    override suspend fun clear(provider: ProviderId) = mutex.withLock {
        val all = readAll().toMutableMap()
        if (all.remove(provider.name) != null || path.exists()) {
            writeAtomicOwnerOnly(path, json.encodeToString(all))
        }
    }

    private fun readAll(): Map<String, AuthTokens> {
        if (!path.exists()) return emptyMap()
        return readOrQuarantine(path) { json.decodeFromString<Map<String, AuthTokens>>(it) } ?: emptyMap()
    }
}

/** App settings; a truncated/malformed file is quarantined and defaults are returned. */

internal class FileSettingsStore(private val path: Path) : SettingsStore {
    private val mutex = Mutex()

    init {
        restrictToOwner(path)
    }

    override suspend fun read(): AppSettings = mutex.withLock {
        if (!path.exists()) return@withLock AppSettings()
        readOrQuarantine(path) { json.decodeFromString<AppSettings>(it) } ?: AppSettings()
    }

    override suspend fun write(settings: AppSettings) = mutex.withLock {
        writeAtomicOwnerOnly(path, json.encodeToString(settings))
    }
}

/**
 * Decodes [path]; on failure renames it to `<name>.corrupt-<epochMs>` (never overwriting the
 * evidence) and returns null so the caller falls back to defaults.
 */

private fun <T> readOrQuarantine(path: Path, decode: (String) -> T): T? {
    val text = try {
        path.readText()
    } catch (e: java.io.IOException) {
        // Unreadable (not malformed): leave the file alone.
        System.err.println("WARN Kainos: could not read $path (${e.message}); using defaults")
        return null
    }
    val failure = try {
        return decode(text)
    } catch (e: Exception) {
        e
    }
    val quarantine = path.resolveSibling("${path.fileName}.corrupt-${System.currentTimeMillis()}")
    val moved = runCatching { Files.move(path, quarantine) }.isSuccess
    System.err.println(
        "WARN Kainos: could not read $path (${failure::class.simpleName}: ${failure.message}); " +
            if (moved) "moved it to ${quarantine.fileName} and using defaults" else "using defaults",
    )
    return null
}

/** Temp file in the same dir (created 0600 on POSIX), then atomic rename over [path]. */

private fun writeAtomicOwnerOnly(path: Path, text: String) {
    val dir = path.toAbsolutePath().parent
    Files.createDirectories(dir)
    val ownerOnly = runCatching {
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))
    }.getOrNull()
    val tmp = runCatching {
        if (ownerOnly != null) Files.createTempFile(dir, "${path.fileName}.", ".tmp", ownerOnly) else null
    }.getOrNull() ?: Files.createTempFile(dir, "${path.fileName}.", ".tmp")
    try {
        Files.writeString(tmp, text)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        runCatching { Files.deleteIfExists(tmp) }
    }
}

/** Best-effort chmod 0600 (file) / 0700 ([directory]); no-op on non-POSIX or missing paths. */

private fun restrictToOwner(path: Path, directory: Boolean = false) {
    if (!path.exists()) return
    runCatching {
        Files.setPosixFilePermissions(
            path,
            PosixFilePermissions.fromString(if (directory) "rwx------" else "rw-------"),
        )
    }
}
