package com.universalmusic.player.platform

import com.universalmusic.player.data.auth.AuthTokens
import com.universalmusic.player.data.settings.AppSettings
import com.universalmusic.player.domain.model.ProviderId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStoresTest {
    private val dir: Path = Files.createTempDirectory("kainos-file-stores")
    private val posix = "posix" in FileSystems.getDefault().supportedFileAttributeViews()

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun corruptSettingsAreQuarantinedAndDefaultsReturned() = runBlocking {
        val path = dir.resolve("settings.json")
        path.writeText("""{"spotifyClientId":"abc","""")
        val store = FileSettingsStore(path)

        assertEquals(AppSettings(), store.read())

        assertFalse(Files.exists(path))
        val quarantined = dir.listDirectoryEntries("settings.json.corrupt-*").single()
        assertEquals("""{"spotifyClientId":"abc","""", quarantined.readText())
    }

    @Test
    fun settingsRoundTripAfterQuarantine() = runBlocking {
        val path = dir.resolve("settings.json")
        path.writeText("not json")
        val store = FileSettingsStore(path)
        store.read()

        store.write(AppSettings(spotifyClientId = "client"))

        assertEquals("client", store.read().spotifyClientId)
        assertEquals(1, dir.listDirectoryEntries("settings.json.corrupt-*").size)
        assertTrue(dir.listDirectoryEntries("*.tmp").isEmpty())
    }

    @Test
    fun corruptTokensAreQuarantinedAndTreatedAsSignedOut() = runBlocking {
        val path = dir.resolve("tokens.json")
        path.writeText("""{"SPOTIFY":{"accessToken":""")
        val store = FileTokenStore(path)

        assertNull(store.read(ProviderId.SPOTIFY))

        assertEquals(1, dir.listDirectoryEntries("tokens.json.corrupt-*").size)
        store.write(ProviderId.SPOTIFY, AuthTokens("fresh"))
        assertEquals("fresh", store.read(ProviderId.SPOTIFY)?.accessToken)
    }

    @Test
    fun writtenTokenFileIsOwnerOnly() = runBlocking {
        if (!posix) return@runBlocking
        val path = dir.resolve("tokens.json")
        val store = FileTokenStore(path)

        store.write(ProviderId.SPOTIFY, AuthTokens("access", refreshToken = "refresh"))

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(path),
        )
    }

    @Test
    fun existingWorldReadableFilesAreTightenedOnCreation() {
        if (!posix) return
        val tokens = dir.resolve("tokens.json")
        val settings = dir.resolve("settings.json")
        tokens.writeText("{}")
        settings.writeText("{}")
        Files.setPosixFilePermissions(tokens, PosixFilePermissions.fromString("rw-r--r--"))
        Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-r--r--"))

        FileTokenStore(tokens)
        FileSettingsStore(settings)

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(tokens)))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(settings)))
    }

    @Test
    fun concurrentTokenWritesDoNotLoseEntries() = runBlocking {
        val path = dir.resolve("tokens.json")
        val store = FileTokenStore(path)

        withContext(Dispatchers.Default) {
            ProviderId.entries.forEach { provider ->
                repeat(40) { round ->
                    launch { store.write(provider, AuthTokens("${provider.name}-$round")) }
                }
            }
        }

        ProviderId.entries.forEach { provider ->
            assertTrue(store.read(provider)?.accessToken.orEmpty().startsWith("${provider.name}-"))
        }
        // A fresh instance sees the same complete file.
        val reread = FileTokenStore(path)
        assertEquals(ProviderId.entries.size, ProviderId.entries.count { reread.read(it) != null })
        assertTrue(dir.listDirectoryEntries("*.tmp").isEmpty())
    }

    @Test
    fun clearRemovesOnlyThatProvider() = runBlocking {
        val store = FileTokenStore(dir.resolve("tokens.json"))
        store.write(ProviderId.SPOTIFY, AuthTokens("spotify"))
        store.write(ProviderId.YOUTUBE_MUSIC, AuthTokens("youtube"))

        store.clear(ProviderId.SPOTIFY)

        assertNull(store.read(ProviderId.SPOTIFY))
        assertEquals("youtube", store.read(ProviderId.YOUTUBE_MUSIC)?.accessToken)
    }
}
