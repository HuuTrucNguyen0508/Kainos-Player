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
import com.universalmusic.player.data.library.FileUserLibraryStore
import com.universalmusic.player.data.library.UserLibraryStore
import com.universalmusic.player.data.playlist.FileKainosPlaylistStore
import com.universalmusic.player.data.playlist.KainosPlaylistStore
import com.universalmusic.player.data.session.FileSessionSnapshotStore
import com.universalmusic.player.data.session.SessionSnapshotStore
import com.universalmusic.player.data.local.JvmLocalTrackSource
import com.universalmusic.player.data.local.LocalLibraryRootMode
import com.universalmusic.player.data.local.JvmLocalLibraryScanCache
import com.universalmusic.player.data.local.LocalLibraryScanCache
import com.universalmusic.player.data.local.LocalLibraryScanConfig
import com.universalmusic.player.data.local.LocalTrackSource
import com.universalmusic.player.data.local.resolveMusicRoots
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.data.settings.SettingsStore
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.playback.PlaybackEngine
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.net.URI
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Properties
import kotlin.io.path.div
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
internal fun homeLanConfigDir(): Path = configDir()

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun isNetworkAvailable(): Boolean = true

actual fun monotonicElapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L

actual fun sha256Bytes(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

actual fun secureRandomBytes(size: Int): ByteArray =
    ByteArray(size).also(SecureRandom()::nextBytes)

actual fun encodeUrl(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

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

actual fun createUserLibraryStore(): UserLibraryStore =
    FileUserLibraryStore(configDir() / "user-library.json")

actual fun createKainosPlaylistStore(): KainosPlaylistStore =
    FileKainosPlaylistStore(configDir() / "kainos-playlists.json")

actual fun createSessionSnapshotStore(): SessionSnapshotStore =
    FileSessionSnapshotStore(configDir() / "playback-session.json")

actual fun createMetadataArtworkCache(): MetadataArtworkCache {
    val disk = FileMetadataCacheDisk(configDir() / "meta-cache")
    return DefaultMetadataArtworkCache(
        disk = disk,
        downloadArtwork = ::downloadArtworkBytes,
    )
}

actual fun createHeartedAudioCache(): HeartedAudioCache =
    DefaultHeartedAudioCache(FileHeartedAudioCacheDisk(configDir() / "audio-cache"))

actual fun createYouTubeAudioDownloader(streams: YouTubeStreamResolver): YouTubeAudioDownloader =
    JvmYouTubeAudioDownloader()

private fun downloadArtworkBytes(url: String): ByteArray? = runCatching {
    URI(url).toURL().openStream().use { input ->
        input.readBytes()
    }
}.getOrNull()?.takeIf { it.isNotEmpty() && it.size <= 2 * 1024 * 1024 }

actual fun createLocalTrackSource(config: () -> LocalLibraryScanConfig): LocalTrackSource =
    JvmLocalTrackSource {
        val scan = config()
        resolveMusicRoots(
            homeDirectory = Paths.get(System.getProperty("user.home", ".")),
            configuredFolders = scan.folders,
            foldersConfigured = scan.mode == LocalLibraryRootMode.EXPLICIT,
            additionalRoots = System.getenv("KAINOS_MUSIC_DIRS"),
        )
    }

actual fun createLocalLibraryScanCache(): LocalLibraryScanCache? = JvmLocalLibraryScanCache()

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

actual fun createPlaybackEngine(spotify: SpotifyPlaybackController): PlaybackEngine =
    DesktopPlaybackEngine(spotify)

actual fun createYouTubeStreamResolver(): YouTubeStreamResolver = JvmYouTubeStreamResolver()

actual fun createSpotifyWebPlaybackHost(tokenSupplier: SpotifyTokenSupplier): SpotifyWebPlaybackHost =
    if (System.getProperty("os.name", "").contains("Linux", ignoreCase = true)) {
        JvmLibrespotPlaybackHost()
    } else {
        JvmSpotifyWebPlaybackHost(tokenSupplier)
    }

actual suspend fun ensureSpotifyConnectClientAvailable(): Boolean = ensureSpotifyDesktopClientRunning()

actual fun openUrl(url: String) {
    val openedWithDesktop = Desktop.isDesktopSupported() && runCatching {
        Desktop.getDesktop().browse(URI(url))
    }.isSuccess
    if (!openedWithDesktop) {
        runCatching { ProcessBuilder("xdg-open", url).start() }
            .getOrElse { error("Could not open the system browser: ${it.message}") }
    }
}

actual fun requiresExplicitSpotifyDevice(): Boolean = false

actual fun platformLabel(): String = "Linux"

actual fun listenForOAuthRedirect(port: Int, path: String): String = awaitOAuthRedirect(port, path)

actual suspend fun authenticateSpotify(authorizationUrl: String, redirectUri: String): String? =
    authenticateWithLoopbackServer(authorizationUrl, redirectUri)

actual fun usesLocalOAuthListener(): Boolean = true

actual fun defaultLocalMusicFolder(): String =
    Paths.get(System.getProperty("user.home", "."), "Music")
        .toAbsolutePath()
        .normalize()
        .toString()

actual fun supportsMusicFolderPicker(): Boolean = true

actual suspend fun pickMusicFolder(): String? {
    pickWithZenity()?.let { return it }
    pickWithKdialog()?.let { return it }
    return pickWithSwing()
}

actual fun releaseMusicFolderAccess(folder: String) = Unit

private var mprisController: MprisController? = null

actual fun bindPlatformMediaControls(
    session: com.universalmusic.player.domain.playback.PlayerSession,
    scope: kotlinx.coroutines.CoroutineScope,
    @Suppress("UNUSED_PARAMETER") toggleFavorite: (com.universalmusic.player.domain.model.Track) -> Boolean,
) {
    mprisController?.stop()
    mprisController = MprisController(session, scope).also { it.start() }
}

actual fun unbindPlatformMediaControls() {
    mprisController?.stop()
    mprisController = null
}

actual fun createHomeLanSyncHub(
    pairing: com.universalmusic.player.data.sync.HomeLanSyncPairing,
    onHearts: suspend (com.universalmusic.player.data.sync.HeartsSyncDocument) -> com.universalmusic.player.data.sync.HeartsSyncDocument,
    onPlaylists: suspend (com.universalmusic.player.data.playlist.PlaylistsSyncDocument) -> com.universalmusic.player.data.playlist.PlaylistsSyncDocument,
    vault: com.universalmusic.player.data.sync.HomeLanVaultStore?,
    heartedVaultFileNames: () -> Set<String>?,
): com.universalmusic.player.data.sync.HomeLanSyncHub {
    val pin = pairing.hubCertSha256Hex
        ?: error("Hub certificate pin missing; re-run Start hub pairing")
    val tls = com.universalmusic.player.data.sync.loadHomeLanHubTls(
        configDir = configDir().toFile(),
        sharedSecretHex = pairing.sharedSecretHex,
        expectedCertSha256Hex = pin,
    )
    return com.universalmusic.player.data.sync.DesktopHomeLanSyncHub(
        pairing = pairing,
        onHearts = onHearts,
        onPlaylists = onPlaylists,
        vault = vault,
        tls = tls,
        heartedVaultFileNames = heartedVaultFileNames,
    )
}

actual fun createHomeLanVaultStore(
    vaultRootProvider: () -> String?,
): com.universalmusic.player.data.sync.HomeLanVaultStore =
    com.universalmusic.player.data.sync.JvmHomeLanVaultStore(
        vaultRootProvider = vaultRootProvider,
        statePath = configDir().resolve("vault-sync-state.json"),
    )

actual fun detectLanHostAddress(): String? {
    return runCatching {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        for (nic in interfaces) {
            if (!nic.isUp || nic.isLoopback) continue
            for (addr in nic.inetAddresses) {
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: continue
                    if (host.startsWith("10.") ||
                        host.startsWith("192.168.") ||
                        host.matches(Regex("""^172\.(1[6-9]|2[0-9]|3[0-1])\..*"""))
                    ) {
                        return host
                    }
                }
            }
        }
        null
    }.getOrNull()
}

actual suspend fun <T> withVaultSyncForeground(
    label: String,
    block: suspend () -> T,
): T = block()

actual fun setHomeLanHubAutostart(enabled: Boolean) {
    val home = System.getenv("HOME") ?: return
    val autostart = java.nio.file.Path.of(home, ".config", "autostart")
    val desktop = autostart.resolve("kainos-home-sync-hub.desktop")
    if (!enabled) {
        runCatching { java.nio.file.Files.deleteIfExists(desktop) }
        return
    }
    runCatching {
        java.nio.file.Files.createDirectories(autostart)
        val exec = System.getenv("KAINOS_PLAYER_EXEC")
            ?: listOf(
                "$home/.local/bin/kainos-player",
                "/usr/local/bin/kainos-player",
            ).firstOrNull { java.io.File(it).canExecute() }
            ?: return
        val body = """
            |[Desktop Entry]
            |Type=Application
            |Name=Kainos Home Sync Hub
            |Comment=Listen for phone library sync on the home LAN
            |Exec=$exec --hub-only
            |X-GNOME-Autostart-enabled=true
            |Terminal=false
            """.trimMargin()
        java.nio.file.Files.writeString(desktop, body)
    }
}

actual suspend fun rematchLocalHeartsAfterVaultSync(): Int {
    // Desktop: local hearts use absolute paths; vault sync keeps those paths stable when
    // the vault root is the music folder. Filename rematch is primarily an Android need.
    return 0
}

private fun pickWithZenity(): String? {
    val zenity = findExecutable("zenity") ?: return null
    return runCatching {
        val process = ProcessBuilder(
            zenity,
            "--file-selection",
            "--directory",
            "--title=Choose music folder",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val stdout = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) null else parsePickedDirectory(stdout)
    }.getOrNull()
}

private fun pickWithKdialog(): String? {
    val kdialog = findExecutable("kdialog") ?: return null
    return runCatching {
        val process = ProcessBuilder(
            kdialog,
            "--getexistingdirectory",
            System.getProperty("user.home", "."),
            "--title",
            "Choose music folder",
        ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val stdout = process.inputStream.bufferedReader().readText()
        if (process.waitFor() != 0) null else parsePickedDirectory(stdout)
    }.getOrNull()
}

/** Keep the last absolute path-looking line; ignore GTK/tool chatter on stdout. */
private fun parsePickedDirectory(stdout: String): String? {
    val candidate = stdout
        .lineSequence()
        .map(String::trim)
        .filter { it.startsWith('/') }
        .lastOrNull()
        ?: return null
    val path = Paths.get(candidate).toAbsolutePath().normalize()
    return path.takeIf { Files.isDirectory(path) }?.toString()
}

private fun pickWithSwing(): String? {
    fun choose(): String? {
        val frame = javax.swing.JFrame().apply {
            title = "Kainos Player"
            isAlwaysOnTop = true
            setLocationRelativeTo(null)
            isVisible = true
            toFront()
        }
        return try {
            val chooser = javax.swing.JFileChooser().apply {
                fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
                dialogTitle = "Choose music folder"
                isMultiSelectionEnabled = false
            }
            val result = chooser.showOpenDialog(frame)
            if (result != javax.swing.JFileChooser.APPROVE_OPTION) null
            else chooser.selectedFile?.absoluteFile?.canonicalFile?.path
        } finally {
            frame.isVisible = false
            frame.dispose()
        }
    }
    return if (javax.swing.SwingUtilities.isEventDispatchThread()) {
        choose()
    } else {
        var selected: String? = null
        javax.swing.SwingUtilities.invokeAndWait { selected = choose() }
        selected
    }
}

private fun findExecutable(name: String): String? {
    val path = System.getenv("PATH") ?: return null
    return path.split(':').firstOrNull { dir ->
        val file = java.io.File(dir, name)
        file.canExecute()
    }?.let { java.io.File(it, name).absolutePath }
}

/**
 * Spotify (and other provider) OAuth tokens. Writes are atomic temp+rename with owner-only
 * permissions; read-modify-write is serialized so concurrent writes can't drop entries.
 * A malformed file is quarantined and treated as signed out, so the user just reconnects.
 */
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
