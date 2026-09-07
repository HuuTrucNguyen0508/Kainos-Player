package com.universalmusic.player.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val KAINOS_SPOTIFY_DEVICE_NAME = "Kainos Player"

internal data class LibrespotPaths(
    val systemCache: Path,
    val audioCache: Path,
    val logFile: Path,
) {
    val credentialsFile: Path get() = systemCache.resolve("credentials.json")

    companion object {
        fun defaults(userHome: Path = Path.of(System.getProperty("user.home", "."))): LibrespotPaths {
            val appDir = userHome.resolve(".universal-music-player")
            return LibrespotPaths(
                systemCache = appDir.resolve("librespot/system"),
                audioCache = appDir.resolve("librespot/audio"),
                logFile = appDir.resolve("logs/librespot.log"),
            )
        }
    }
}

internal interface LibrespotProcess {
    val isAlive: Boolean
    val exitCode: Int?
    fun stop()
}

internal interface LibrespotRuntime {
    fun findExecutable(): Path?
    fun start(executable: Path, arguments: List<String>, logFile: Path): LibrespotProcess
}

/** A Linux Spotify Connect receiver owned by the app process. */
internal class JvmLibrespotPlaybackHost(
    private val runtime: LibrespotRuntime = SystemLibrespotRuntime,
    private val paths: LibrespotPaths = LibrespotPaths.defaults(),
    private val startupDelayMillis: Long = 750,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) : SpotifyWebPlaybackHost {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<SpotifyWebPlaybackState>(SpotifyWebPlaybackState.Stopped)
    override val state: StateFlow<SpotifyWebPlaybackState> = _state.asStateFlow()
    override val requiresStreamingScope: Boolean = false
    private var process: LibrespotProcess? = null

    private val shutdownHook = if (runtime === SystemLibrespotRuntime) {
        Thread({ process?.stop() }, "kainos-librespot-shutdown").also(Runtime.getRuntime()::addShutdownHook)
    } else {
        null
    }

    override suspend fun ensureDeviceReady(): SpotifyWebPlaybackDevice? = mutex.withLock {
        ensureStarted(interactive = false)
    }

    override suspend fun prepareAuthentication(): SpotifyWebPlaybackDevice? = mutex.withLock {
        securePaths()
        process?.takeIf { it.isAlive }?.let {
            _state.value = SpotifyWebPlaybackState.ConnectingSpotify
            return@withLock namedDevice()
        }
        process?.stop()
        process = null
        val rejectedCredentials = (_state.value as? SpotifyWebPlaybackState.Failed)
            ?.reason
            .let { it as? SpotifyWebPlaybackFailure.LibrespotExited }
            ?.detail
            ?.contains("rejected", ignoreCase = true) == true
        if (rejectedCredentials) {
            // This method is only called by the explicit Settings setup action.
            // Removing a rejected reusable blob makes librespot run OAuth again.
            runCatching { Files.deleteIfExists(paths.credentialsFile) }
        }
        ensureStarted(interactive = !hasCachedCredentials())
    }

    private suspend fun ensureStarted(interactive: Boolean): SpotifyWebPlaybackDevice? {
        securePaths()
        process?.let { existing ->
            if (existing.isAlive) {
                _state.value = SpotifyWebPlaybackState.ConnectingSpotify
                return namedDevice()
            }
            process = null
            if (!interactive) {
                _state.value = SpotifyWebPlaybackState.Failed(
                    SpotifyWebPlaybackFailure.LibrespotExited(classifyExit(existing.exitCode)),
                )
                return null
            }
        }

        val executable = runtime.findExecutable()
        if (executable == null) {
            _state.value = SpotifyWebPlaybackState.Failed(SpotifyWebPlaybackFailure.LibrespotNotFound)
            return null
        }
        if (!interactive && !hasCachedCredentials()) {
            _state.value = SpotifyWebPlaybackState.Failed(SpotifyWebPlaybackFailure.LibrespotAuthenticationRequired)
            return null
        }

        _state.value = SpotifyWebPlaybackState.StartingHost
        val arguments = buildList {
            addAll(
                listOf(
                    "--name", KAINOS_SPOTIFY_DEVICE_NAME,
                    "--system-cache", paths.systemCache.toString(),
                    "--cache", paths.audioCache.toString(),
                    "--disable-audio-cache",
                    "--disable-discovery",
                    "--device-type", "computer",
                    "--bitrate", "320",
                    "--format", "S16",
                ),
            )
            if (interactive) add("--enable-oauth")
        }
        val started = runCatching { runtime.start(executable, arguments, paths.logFile) }
            .getOrElse { failure ->
                _state.value = SpotifyWebPlaybackState.Failed(
                    SpotifyWebPlaybackFailure.LibrespotExited(
                        failure.message?.takeIf { it.isNotBlank() } ?: "could not start the process",
                    ),
                )
                return null
            }
        process = started
        wait(startupDelayMillis)
        if (!started.isAlive) {
            process = null
            _state.value = SpotifyWebPlaybackState.Failed(
                SpotifyWebPlaybackFailure.LibrespotExited(classifyExit(started.exitCode)),
            )
            return null
        }
        _state.value = SpotifyWebPlaybackState.ConnectingSpotify
        return namedDevice()
    }

    override suspend fun shutdown() = mutex.withLock {
        process?.stop()
        process = null
        secureCredentialFile()
        _state.value = SpotifyWebPlaybackState.Stopped
    }

    private fun namedDevice() = SpotifyWebPlaybackDevice(deviceName = KAINOS_SPOTIFY_DEVICE_NAME)

    private fun hasCachedCredentials(): Boolean =
        runCatching { Files.isRegularFile(paths.credentialsFile) && Files.size(paths.credentialsFile) > 0L }
            .getOrDefault(false)

    private fun securePaths() {
        Files.createDirectories(paths.systemCache)
        Files.createDirectories(paths.audioCache)
        Files.createDirectories(paths.logFile.parent)
        setPermissions(paths.systemCache.parent, DIRECTORY_PERMISSIONS)
        setPermissions(paths.systemCache, DIRECTORY_PERMISSIONS)
        setPermissions(paths.audioCache, DIRECTORY_PERMISSIONS)
        if (!Files.exists(paths.logFile)) Files.createFile(paths.logFile)
        setPermissions(paths.logFile, FILE_PERMISSIONS)
        secureCredentialFile()
    }

    private fun secureCredentialFile() {
        if (Files.exists(paths.credentialsFile)) setPermissions(paths.credentialsFile, FILE_PERMISSIONS)
    }

    private fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        runCatching { Files.setPosixFilePermissions(path, permissions) }
    }

    private fun classifyExit(exitCode: Int?): String {
        val log = runCatching { Files.readString(paths.logFile).takeLast(16_384).lowercase() }.getOrDefault("")
        return when {
            "invalid_credentials" in log || "invalid credentials" in log || "login request was denied" in log ->
                "Spotify rejected the cached librespot credentials. Set up in-app playback again in Settings."
            "credentials are required" in log || "authentication is not possible" in log ->
                "librespot has no usable credentials. Set up in-app playback in Settings."
            "audio backend" in log || "audio sink" in log || "could not start audio" in log ->
                "librespot could not open the Linux audio output. See ${paths.logFile}."
            else -> "librespot exited with code ${exitCode ?: "unknown"}. See ${paths.logFile}."
        }
    }

    private companion object {
        val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}

internal object SystemLibrespotRuntime : LibrespotRuntime {
    override fun findExecutable(): Path? = findLibrespotExecutable()

    override fun start(executable: Path, arguments: List<String>, logFile: Path): LibrespotProcess {
        val command = listOf(executable.toString()) + arguments
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
            .apply {
                // A Web API token is not a substitute for librespot's reusable native credentials.
                environment().remove("LIBRESPOT_ACCESS_TOKEN")
                environment().remove("LIBRESPOT_ENABLE_OAUTH")
            }
            .start()
        return JvmLibrespotProcess(process)
    }
}

private class JvmLibrespotProcess(private val process: Process) : LibrespotProcess {
    override val isAlive: Boolean get() = process.isAlive
    override val exitCode: Int? get() = if (process.isAlive) null else runCatching(process::exitValue).getOrNull()

    override fun stop() {
        if (!process.isAlive) return
        process.destroy()
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
        }
    }
}

internal fun findLibrespotExecutable(
    environment: Map<String, String> = System.getenv(),
    userHome: Path = Path.of(System.getProperty("user.home", ".")),
    workingDirectory: Path = Path.of("").toAbsolutePath(),
): Path? {
    val explicit = environment["KAINOS_LIBRESPOT"]?.takeIf(String::isNotBlank)?.let(Path::of)
    val candidates = buildList {
        explicit?.let(::add)
        add(workingDirectory.resolve("tools/librespot-runtime/bin/librespot"))
        add(userHome.resolve(".local/bin/librespot"))
        environment["PATH"].orEmpty().split(File.pathSeparatorChar)
            .filter(String::isNotBlank)
            .mapTo(this) { Path.of(it).resolve("librespot") }
    }
    return candidates
        .asSequence()
        .map { if (it.isAbsolute) it else workingDirectory.resolve(it) }
        .map(Path::normalize)
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}
