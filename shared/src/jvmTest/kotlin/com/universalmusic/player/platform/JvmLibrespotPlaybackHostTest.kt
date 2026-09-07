package com.universalmusic.player.platform

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JvmLibrespotPlaybackHostTest {
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
            assertFalse("--enable-oauth" in runtime.starts.single())
            assertFalse("--access-token" in runtime.starts.single())
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
        val child = object : LibrespotProcess {
            override var isAlive = alive
            override val exitCode: Int? get() = if (isAlive) null else 0
            override fun stop() { isAlive = false }
        }
        override fun findExecutable(): Path? = if (installed) Path.of("/fake/librespot") else null
        override fun start(executable: Path, arguments: List<String>, logFile: Path): LibrespotProcess {
            starts += arguments
            return child
        }
    }
}
