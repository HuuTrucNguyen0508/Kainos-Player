package com.universalmusic.player.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmLibrespotPlaybackHostTest {
    @Test
    fun firstPlayAfterReceiverCrashRestartsWithCachedCredentials() = runBlocking {
        fixture(cached = true) { host, runtime, _ ->
            assertNotNull(host.ensureDeviceReady())
            runtime.child.isAlive = false
            assertNotNull(host.ensureDeviceReady())
            assertEquals(2, runtime.starts.size)
        }
    }

    @Test
    fun eventProgramDeliversOrderedReceiverEventsAndShutdownRemovesIt() = runBlocking {
        fixture(cached = true) { host, runtime, _ ->
            assertFalse(host.receiverEvents.isAvailable)
            host.ensureDeviceReady()
            assertTrue(host.receiverEvents.isAvailable)
            val args = runtime.starts.single()
            val program = Path.of(args[args.indexOf("--onevent") + 1])
            val received = Channel<SpotifyReceiverEvent>(Channel.UNLIMITED)
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                host.receiverEvents.events.collect { received.send(it) }
            }
            try {
                val track = "69MiXsrEJYwMGZzisvSjV7"
                for (kind in listOf("playing", "end_of_track", "stopped")) {
                    val child = ProcessBuilder(program.toString()).apply {
                        environment()["PLAYER_EVENT"] = kind
                        environment()["TRACK_ID"] = track
                        environment()["POSITION_MS"] = "201"
                    }.start()
                    assertTrue(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
                    assertEquals(0, child.exitValue())
                }
                val events = withTimeout(2_000) { List(3) { received.receive() } }
                assertEquals(
                    listOf(SpotifyReceiverEvent.Kind.PLAYING, SpotifyReceiverEvent.Kind.END_OF_TRACK,
                        SpotifyReceiverEvent.Kind.STOPPED),
                    events.map { it.kind },
                )
                assertTrue(events.all { it.trackId == track })
                assertEquals(201, events.first().positionMs)
                val permissions = Files.getPosixFilePermissions(program.parent)
                assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"), permissions)
                host.shutdown()
                assertFalse(host.receiverEvents.isAvailable)
                assertFalse(Files.exists(program.parent))
            } finally {
                collector.cancel()
            }
        }
    }

    @Test
    fun malformedAndNonPlayerEventsCannotChangePlayback() {
        assertNull(parseLibrespotEvent("sink\t69MiXsrEJYwMGZzisvSjV7\t0"))
        assertNull(parseLibrespotEvent("end_of_track\twrong\t"))
        assertNull(parseLibrespotEvent("playing\t69MiXsrEJYwMGZzisvSjV7\t-1"))
        assertNull(parseLibrespotEvent("paused\t69MiXsrEJYwMGZzisvSjV7\t"))
        assertEquals(
            SpotifyReceiverEvent(SpotifyReceiverEvent.Kind.END_OF_TRACK, "69MiXsrEJYwMGZzisvSjV7"),
            parseLibrespotEvent("end_of_track\t69MiXsrEJYwMGZzisvSjV7\t"),
        )
    }

    @Test
    fun playbackWithoutCredentialsDoesNotOpenBrowserOrStartProcess() = runBlocking {
        fixture { host, runtime, _ ->
            assertNull(host.ensureDeviceReady())
            assertEquals(0, runtime.starts.size)
            assertEquals(
                SpotifyWebPlaybackState.Failed(SpotifyWebPlaybackFailure.LibrespotAuthenticationRequired),
                host.state.value,
            )
        }
    }

    @Test
    fun cachedPlaybackStartsOneHeadlessReceiverAndShutdownStopsIt() = runBlocking {
        fixture(cached = true) { host, runtime, _ ->
            assertEquals("Kainos Player", host.ensureDeviceReady()?.deviceName)
            host.ensureDeviceReady()
            assertEquals(1, runtime.starts.size)
            val args = runtime.starts.single()
            assertFalse("--enable-oauth" in args)
            assertFalse("--access-token" in args)
            assertTrue("--enable-volume-normalisation" in args)
            val volumeIndex = args.indexOf("--initial-volume")
            assertTrue(volumeIndex >= 0)
            assertEquals("100", args.getOrNull(volumeIndex + 1))
            val pregainIndex = args.indexOf("--normalisation-pregain")
            assertTrue(pregainIndex >= 0)
            assertEquals("3", args.getOrNull(pregainIndex + 1))
            host.shutdown()
            assertFalse(runtime.child.isAlive)
            assertEquals(SpotifyWebPlaybackState.Stopped, host.state.value)
        }
    }

    @Test
    fun explicitSetupEnablesOneTimeBrowserAuthentication() = runBlocking {
        fixture { host, runtime, _ ->
            assertEquals("Kainos Player", host.prepareAuthentication()?.deviceName)
            assertTrue("--enable-oauth" in runtime.starts.single())
        }
    }

    @Test
    fun explicitSetupReusesHealthyCredentialsWithoutOpeningBrowser() = runBlocking {
        fixture(cached = true) { host, runtime, paths ->
            assertEquals("Kainos Player", host.prepareAuthentication()?.deviceName)
            assertFalse("--enable-oauth" in runtime.starts.single())
            assertTrue(Files.exists(paths.credentialsFile))
        }
    }

    @Test
    fun zeroExitCodeFromRejectedLoginIsStillFailure() = runBlocking {
        fixture(cached = true, alive = false) { host, _, paths ->
            Files.createDirectories(paths.logFile.parent)
            Files.writeString(paths.logFile, "could not initialize spirc: Login request was denied: INVALID_CREDENTIALS")
            assertNull(host.ensureDeviceReady())
            val failure = (host.state.value as SpotifyWebPlaybackState.Failed).reason
            assertTrue(failure is SpotifyWebPlaybackFailure.LibrespotExited)
            assertTrue(failure.detail.orEmpty().contains("credentials", ignoreCase = true))
        }
    }

    @Test
    fun setupAfterRejectedLoginClearsCredentialAndRunsOauth() = runBlocking {
        fixture(cached = true, alive = false) { host, runtime, paths ->
            Files.createDirectories(paths.logFile.parent)
            Files.writeString(paths.logFile, "could not initialize spirc: Login request was denied: INVALID_CREDENTIALS")
            assertNull(host.ensureDeviceReady())

            host.prepareAuthentication()

            assertTrue("--enable-oauth" in runtime.starts.last())
            assertFalse(Files.exists(paths.credentialsFile))
        }
    }

    @Test
    fun missingBinaryReportsInstallationFailure() = runBlocking {
        fixture(cached = true, installed = false) { host, runtime, _ ->
            assertNull(host.ensureDeviceReady())
            assertEquals(0, runtime.starts.size)
            assertEquals(
                SpotifyWebPlaybackState.Failed(SpotifyWebPlaybackFailure.LibrespotNotFound),
                host.state.value,
            )
        }
    }

    @Test
    fun locatorFindsRepositoryInstallationWithoutLauncher() {
        val root = Files.createTempDirectory("kainos-librespot-locator")
        try {
            val executable = root.resolve("tools/librespot-runtime/bin/librespot")
            Files.createDirectories(executable.parent)
            Files.writeString(executable, "#!/bin/sh\n")
            executable.toFile().setExecutable(true)
            assertEquals(executable, findLibrespotExecutable(emptyMap(), root.resolve("home"), root))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun locatorFindsRepositoryInstallationFromDesktopAppCwd() {
        val root = Files.createTempDirectory("kainos-librespot-locator-parent")
        try {
            val executable = root.resolve("tools/librespot-runtime/bin/librespot")
            Files.createDirectories(executable.parent)
            Files.writeString(executable, "#!/bin/sh\n")
            executable.toFile().setExecutable(true)
            val desktopCwd = root.resolve("desktopApp")
            Files.createDirectories(desktopCwd)
            assertEquals(
                executable.normalize(),
                findLibrespotExecutable(emptyMap(), root.resolve("home"), desktopCwd)?.normalize(),
            )
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private suspend fun fixture(
        cached: Boolean = false,
        alive: Boolean = true,
        installed: Boolean = true,
        action: suspend (JvmLibrespotPlaybackHost, FakeRuntime, LibrespotPaths) -> Unit,
    ) {
        val root = Files.createTempDirectory("kainos-librespot-test")
        val paths = LibrespotPaths(root.resolve("system"), root.resolve("audio"), root.resolve("logs/receiver.log"))
        val runtime = FakeRuntime(alive, installed)
        val host = JvmLibrespotPlaybackHost(runtime, paths, wait = {})
        try {
            if (cached) {
                Files.createDirectories(paths.systemCache)
                Files.writeString(paths.credentialsFile, "{}")
            }
            action(host, runtime, paths)
        } finally {
            host.shutdown()
            root.toFile().deleteRecursively()
        }
    }

    private class FakeRuntime(alive: Boolean, private val installed: Boolean) : LibrespotRuntime {
        val starts = mutableListOf<List<String>>()
        class Child(alive: Boolean) : LibrespotProcess {
            override var isAlive = alive
            override val exitCode: Int? get() = if (isAlive) null else 0
            override fun stop() { isAlive = false }
        }
        val child = Child(alive)
        override fun findExecutable(): Path? = if (installed) Path.of("/fake/librespot") else null
        override fun start(executable: Path, arguments: List<String>, logFile: Path): LibrespotProcess {
            starts += arguments
            if (starts.size > 1) child.isAlive = true
            return child
        }
    }
}
