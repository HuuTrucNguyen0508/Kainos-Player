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
    @Test fun absentDefaultMusicIsAnEmptySuccessfulScan() = withTempDirectory { home ->
        val oldHome = System.getProperty("user.home")
        try {
            System.setProperty("user.home", home.toString())
            val provider = LocalMusicProvider(com.universalmusic.player.platform.createLocalTrackSource {
                LocalLibraryScanConfig()
            })
            assertTrue(provider.refresh().isEmpty())
            assertEquals(com.universalmusic.player.domain.model.ProviderState.AVAILABLE, provider.state.value)
        } finally { System.setProperty("user.home", oldHome) }
    }

    @Test fun missingDefaultDoesNotHideAnExtraRoot() = withTempDirectory { home ->
        val extra = Files.createDirectory(home.resolve("Extra"))
        Files.write(extra.resolve("track.mp3"), byteArrayOf())
        val source = JvmLocalTrackSource(optionalRootsProvider = { setOf(home.resolve("Music")) }) { resolveMusicRoots(home, additionalRoots = extra.toString()) }
        assertEquals(1, source.scan().size)
    }

    @Test fun connectedRootsRefreshWhileDisconnectedRootsKeepOnlyTheirOwnCache() = withTempDirectory { home ->
        val a = Files.createDirectory(home.resolve("A"))
        val b = Files.createDirectory(home.resolve("B"))
        val aSong = Files.write(a.resolve("old.mp3"), byteArrayOf())
        Files.write(b.resolve("kept.mp3"), byteArrayOf())
        var roots = listOf(a, b)
        val provider = LocalMusicProvider(JvmLocalTrackSource { roots })
        assertEquals(2, provider.refresh().size)
        b.toFile().deleteRecursively()
        Files.delete(aSong)
        Files.write(a.resolve("new.mp3"), byteArrayOf())
        assertEquals(setOf("new", "kept"), provider.refresh().map { it.title }.toSet())
        assertEquals(listOf(b.toString()), provider.scanResult.value!!.unavailableRoots)
        assertEquals(1, provider.scanResult.value!!.readableRootCount)
        assertEquals(com.universalmusic.player.domain.model.ProviderState.AVAILABLE, provider.state.value)
        roots = listOf(b)
        assertEquals(listOf("kept"), provider.refresh().map { it.title })
        Files.createDirectory(b)
        Files.write(b.resolve("kept.mp3"), byteArrayOf())
        assertEquals(listOf("kept"), provider.refresh().map { it.title })
    }

    @Test fun unreadableNestedFolderKeepsItsCachedTracks() = withTempDirectory { root ->
        val open = Files.createDirectory(root.resolve("Open"))
        val locked = Files.createDirectory(root.resolve("Locked"))
        Files.write(open.resolve("open.mp3"), byteArrayOf())
        Files.write(locked.resolve("locked.mp3"), byteArrayOf())
        val provider = LocalMusicProvider(JvmLocalTrackSource(listOf(root)))
        assertEquals(setOf("open", "locked"), provider.refresh().map { it.title }.toSet())
        val permissions = Files.getPosixFilePermissions(locked)
        Files.setPosixFilePermissions(locked, emptySet())
        try {
            // Root ignores permissions, so there is nothing to test.
            if (Files.isReadable(locked)) return@withTempDirectory
            assertEquals(setOf("open", "locked"), provider.refresh().map { it.title }.toSet())
            assertEquals(emptyList(), provider.scanResult.value!!.unavailableRoots)
        } finally {
            Files.setPosixFilePermissions(locked, permissions)
        }
    }

    @Test fun disconnectedRootKeepsCachedTracksAndReportsUnavailable() = withTempDirectory { root ->
        val folder = Files.createDirectory(root.resolve("External Music"))
        val source = JvmLocalTrackSource(listOf(folder))
        val provider = LocalMusicProvider(source)
        provider.applyCachedTracks(listOf(LocalTrack("cached", "Remembered", location = folder.resolve("song.flac").toUri().toString())))
        Files.delete(folder)
        provider.refresh()
        assertTrue(provider.scanResult.value!!.unavailableRoots.single().contains("External Music"), "missing folder must be named")
        assertEquals(com.universalmusic.player.domain.model.ProviderState.UNAVAILABLE, provider.state.value)
        assertEquals("Remembered", provider.libraryTracks.value.single().title)
        Files.createDirectory(folder)
        assertTrue(provider.refresh().isEmpty(), "a reachable empty folder is empty")
        assertEquals(com.universalmusic.player.domain.model.ProviderState.AVAILABLE, provider.state.value)
    }

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
    fun deduplicatesNormalizedPaths() = withTempDirectory { root ->
        Files.write(root.resolve("track.ogg"), byteArrayOf())
        val source = JvmLocalTrackSource(
            listOf(
                root,
                root.resolve("."),
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
            foldersConfigured = true,
            additionalRoots = extra.toString(),
        )

        assertEquals(
            listOf(chosen, extra).map { it.toAbsolutePath().normalize() },
            roots,
        )
    }

    @Test
    fun emptyConfiguredFoldersDoNotReintroduceDefaultMusic() = withTempDirectory { root ->
        val roots = resolveMusicRoots(
            homeDirectory = root,
            configuredFolders = emptyList(),
            foldersConfigured = true,
            additionalRoots = null,
        )
        assertTrue(roots.isEmpty())
    }

    @Test
    fun sameAlbumTitleInDifferentFoldersGetsDistinctAlbumGroupKeys() = withTempDirectory { root ->
        val a = Files.createDirectories(root.resolve("Artist_A/Greatest_Hits"))
        val b = Files.createDirectories(root.resolve("Artist_B/Greatest_Hits"))
        Files.write(a.resolve("01 - Song_One.mp3"), byteArrayOf(1))
        Files.write(b.resolve("01 - Song_Two.mp3"), byteArrayOf(2))

        val tracks = JvmLocalTrackSource(listOf(root)).scan()
        assertEquals(2, tracks.size)
        assertTrue(tracks[0].albumGroupKey != tracks[1].albumGroupKey)
        assertEquals("Greatest Hits", tracks[0].album)
        assertEquals("Greatest Hits", tracks[1].album)
    }

    @Test
    fun prefersSidecarCoverWhenPresent() = withTempDirectory { root ->
        val album = Files.createDirectories(root.resolve("Signal_Club/City_Lines"))
        Files.write(album.resolve("01 - Blue_Hour.mp3"), byteArrayOf(1, 2, 3))
        val cover = Files.write(album.resolve("cover.jpg"), ByteArray(64) { 7 })

        val track = JvmLocalTrackSource(listOf(root)).scan().single()
        assertEquals(cover.toUri().toASCIIString(), track.artworkUri)
    }

    @Test
    fun unchangedFilesAreReusedWithoutAnotherProbe() = withTempDirectory { root ->
        Files.write(root.resolve("01 - Blue_Hour.mp3"), byteArrayOf(1, 2, 3))
        val source = JvmLocalTrackSource(listOf(root))
        val first = source.scanReusing(emptyList())
        assertEquals(1, first.probed)
        assertEquals(0, first.reused)
        val second = source.scanReusing(first.tracks)
        assertEquals(0, second.probed)
        assertEquals(1, second.reused)
        assertEquals(first.tracks.single().title, second.tracks.single().title)

        val file = root.resolve("01 - Blue_Hour.mp3")
        Files.write(file, byteArrayOf(9, 9, 9, 9))
        val third = source.scanReusing(second.tracks)
        assertEquals(1, third.probed)
        assertEquals(0, third.reused)
    }

    @Test
    fun rowsCachedBeforeTagsWereReadAreProbedAgainOnce() = withTempDirectory { root ->
        Files.write(root.resolve("01 - Blue_Hour.mp3"), byteArrayOf(1, 2, 3))
        val source = JvmLocalTrackSource(listOf(root))
        val first = source.scanReusing(emptyList())
        assertTrue(first.tracks.single().tagsRead)
        val olderBuild = first.tracks.map { it.copy(tagsVersion = 0) }

        val second = source.scanReusing(olderBuild)

        assertEquals(1, second.probed)
        assertTrue(second.tracks.single().tagsRead)
        assertEquals(1, source.scanReusing(second.tracks).reused)
    }

    /** A real FLAC tagged the way Qobuz downloads are, read through the desktop scan and provider. */
    @Test
    fun flacTagsFillAlbumArtistPositionAndYear() = withTempDirectory { root ->
        if (!commandExists("ffmpeg") || !commandExists("ffprobe")) return@withTempDirectory
        val folder = Files.createDirectories(root.resolve("Playlist"))
        val first = folder.resolve("HOYO-MiX - TruE.flac")
        val second = folder.resolve("y2mate.com - zzz_dldnT46oCrs_320kbps.flac")
        writeTaggedFlac(first, "TITLE=TruE", "ARTIST=HOYO-MiX", "ALBUMARTIST=HOYO-MiX;黄龄", "ALBUM=TruE (Single)",
            "TRACKNUMBER=2", "DISCNUMBER=1", "DATE=2022-08-13", "GENRE=Électronique")
        writeTaggedFlac(second, "ALBUM=TruE (Single)", "ALBUMARTIST=HOYO-MiX;黄龄", "TRACKNUMBER=1", "DISCNUMBER=1")

        val provider = LocalMusicProvider(JvmLocalTrackSource(listOf(root)))
        val tracks = provider.refresh().associateBy { it.title }

        val tagged = tracks.getValue("TruE")
        assertEquals(listOf("HOYO-MiX"), tagged.artists.map { it.name })
        assertEquals("TruE (Single)", tagged.album?.title)
        assertEquals(listOf("HOYO-MiX", "黄龄"), tagged.album?.artists?.map { it.name })
        assertEquals(2, tagged.trackNumber)
        assertEquals(1, tagged.discNumber)
        assertEquals(2022, tagged.year)
        assertEquals("Électronique", tagged.genre)
        assertTrue((tagged.durationMs ?: 0) > 0)
        // No title tag: the cleaned filename; no artist tag: the album artist.
        val untitled = tracks.getValue("zzz")
        assertEquals(listOf("HOYO-MiX", "黄龄"), untitled.artists.map { it.name })
        assertEquals(tagged.album?.canonicalId, untitled.album?.canonicalId)
        assertEquals(
            listOf("zzz", "TruE"),
            tracks.values.toList().let(com.universalmusic.player.domain.model.TrackSort.ALBUM_ASCENDING::sort).map { it.title },
        )
    }
}

private fun commandExists(name: String): Boolean =
    runCatching { ProcessBuilder(name, "-version").redirectErrorStream(true).start().apply { inputStream.readBytes() }.waitFor() == 0 }
        .getOrDefault(false)

private fun writeTaggedFlac(target: Path, vararg tags: String) {
    val command = mutableListOf("ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "anullsrc=r=44100:cl=mono", "-t", "0.2")
    tags.forEach { command += listOf("-metadata", it.substringBefore('=') + "=" + it.substringAfter('=')) }
    command += target.toString()
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.readBytes().decodeToString()
    check(process.waitFor() == 0) { "ffmpeg failed: $output" }
}

private fun withTempDirectory(test: suspend (Path) -> Unit) = runTest {
    val directory = createTempDirectory("kainos-local-source-test")
    try {
        test(directory)
    } finally {
        directory.toFile().deleteRecursively()
    }
}
