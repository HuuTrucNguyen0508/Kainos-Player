package com.universalmusic.player.data.local

import com.universalmusic.player.domain.model.QualityTier
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JvmLocalTrackSourceTest {
    @Test
    fun scansSupportedFilesAndDerivesDirectoryMetadata() = withTempDirectory { root ->
        val album = Files.createDirectories(root.resolve("Signal_Club/City_Lines"))
        val flac = Files.write(album.resolve("01 - Blue_Hour.FLAC"), byteArrayOf())
        Files.write(album.resolve("cover.jpg"), byteArrayOf())

        val tracks = JvmLocalTrackSource(listOf(root)).scan()

        assertEquals(1, tracks.size)
        with(tracks.single()) {
            assertEquals("Blue Hour", title)
            assertEquals(listOf("Signal Club"), artists)
            assertEquals("City Lines", this.album)
            assertEquals(flac.toAbsolutePath().normalize().toUri().toASCIIString(), location)
            assertEquals(QualityTier.LOSSLESS, quality?.tier)
            assertEquals("flac", quality?.codec)
        }
    }

    @Test
    fun filenameArtistOverridesTheDirectoryArtist() = withTempDirectory { root ->
        val album = Files.createDirectories(root.resolve("Directory Artist/An Album"))
        Files.write(album.resolve("07 - File Artist - A Song.mp3"), byteArrayOf())

        val track = JvmLocalTrackSource(listOf(root)).scan().single()

        assertEquals("A Song", track.title)
        assertEquals(listOf("File Artist"), track.artists)
        assertEquals("An Album", track.album)
        assertEquals(QualityTier.STANDARD, track.quality?.tier)
        assertEquals("mp3", track.quality?.codec)
    }

    @Test
    fun skipsMissingRootsAndDeduplicatesNormalizedPaths() = withTempDirectory { root ->
        Files.write(root.resolve("track.ogg"), byteArrayOf())
        val source = JvmLocalTrackSource(
            listOf(
                root,
                root.resolve("."),
                root.resolve("missing"),
            ),
        )

        val firstScan = source.scan()
        val secondScan = source.scan()

        assertEquals(1, firstScan.size)
        assertEquals(firstScan.single().id, secondScan.single().id)
        assertTrue(firstScan.single().location.startsWith("file:"))
    }

    @Test
    fun resolvesDefaultAndEnvironmentRootsWithThePlatformSeparator() = withTempDirectory { root ->
        val extra = root.resolve("extra")
        val another = root.resolve("another")
        val environmentValue = listOf(extra, another, extra.resolve(".")).joinToString(File.pathSeparator)

        val roots = resolveMusicRoots(root, additionalRoots = environmentValue)

        assertEquals(
            listOf(root.resolve("Music"), extra, another).map { it.toAbsolutePath().normalize() },
            roots,
        )
    }

    @Test
    fun configuredFoldersReplaceTheDefaultMusicDirectory() = withTempDirectory { root ->
        val chosen = root.resolve("vinyl")
        val extra = root.resolve("extra")

        val roots = resolveMusicRoots(
            homeDirectory = root,
            configuredFolders = listOf(chosen.toString(), "  ", chosen.resolve(".").toString()),
            additionalRoots = extra.toString(),
        )

        assertEquals(
            listOf(chosen, extra).map { it.toAbsolutePath().normalize() },
            roots,
        )
    }
}

private fun withTempDirectory(test: suspend (Path) -> Unit) = runTest {
    val directory = createTempDirectory("kainos-local-source-test")
    try {
        test(directory)
    } finally {
        directory.toFile().deleteRecursively()
    }
}
