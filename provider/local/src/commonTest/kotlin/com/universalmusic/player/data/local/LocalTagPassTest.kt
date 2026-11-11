package com.universalmusic.player.data.local

import com.universalmusic.player.data.library.toPersisted
import com.universalmusic.player.data.sync.localSyncIdentity
import com.universalmusic.player.data.sync.toLocalFileHeartExport
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.model.inAlbumOrder
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Android's lazy tag pass over SAF files, driven through [LocalMusicProvider] as the app does. */
class LocalTagPassTest {
    private val tree = "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/"
    private val key = "lc1:" + "ab".repeat(32)

    /** What the SAF scan produces: folder name as album, filename guesses, no duration. */
    private fun safRow(id: String, fileName: String, contentKey: String? = null) = LocalTrack(
        id = id,
        title = filenameTags(fileName).title,
        artists = listOfNotNull(filenameTags(fileName).artist),
        album = "Downloads",
        albumGroupKey = tree + "primary%3AMusic%2FDownloads",
        location = tree + "primary%3AMusic%2FDownloads%2F" + fileName.replace(" ", "%20"),
        contentLength = 1_000,
        fileModifiedEpochMs = 5_000,
        contentKey = contentKey,
    )

    @Test fun backgroundPassFillsTagsAndTheCacheKeepsThemForTheNextLaunch() = runTest {
        val cache = MemoryScanCache()
        val reader = FakeTagReader(
            "RuLe" to LocalTags(
                title = "RuLe", artists = listOf("Ado"), album = "Kyogen", albumArtist = "Ado",
                trackNumber = 4, discNumber = 1, year = 2022, genre = "J-Pop", durationMs = 201_000,
            ),
        )
        val provider = LocalMusicProvider(
            LocalTrackSource { listOf(safRow("rule", "Ado - RuLe.flac"), safRow("bare", "A_Different_Sky.flac")) },
            cache, { "k" }, tagReader = reader,
        )
        val scanned = provider.refresh()
        assertEquals(emptyList(), reader.opened, "the scan must not open files")
        assertEquals("Downloads", scanned.first().album?.title)

        provider.enrichTags()

        val rule = provider.libraryTracks.value.first()
        assertEquals("RuLe", rule.title)
        assertEquals(listOf("Ado"), rule.artists.map { it.name })
        assertEquals("Kyogen", rule.album?.title)
        assertEquals(listOf("Ado"), rule.album?.artists?.map { it.name })
        assertEquals(2022, rule.album?.year)
        assertEquals(4, rule.trackNumber)
        assertEquals(1, rule.discNumber)
        assertEquals(2022, rule.year)
        assertEquals("J-Pop", rule.genre)
        assertEquals(201_000, rule.durationMs)
        assertEquals(5_000, rule.addedAtMs)
        // A file without tags keeps its cleaned filename and folder album, and is not reopened.
        val bare = provider.libraryTracks.value.last()
        assertEquals("A Different Sky", bare.title)
        assertEquals("Downloads", bare.album?.title)
        provider.enrichTags()
        assertEquals(listOf("RuLe", "Different"), reader.opened)

        val restarted = LocalMusicProvider(LocalTrackSource { emptyList() }, cache, { "k" }, tagReader = reader)
        restarted.hydrateFromCache()
        assertEquals("Kyogen", restarted.libraryTracks.value.first().album?.title)
        assertEquals(4, restarted.libraryTracks.value.first().trackNumber)
        restarted.enrichTags()
        assertEquals(listOf("RuLe", "Different"), reader.opened)
    }

    @Test fun aRescanThatOnlySeesFileNamesKeepsTagsOfUnchangedFilesAndRereadsChangedOnes() = runTest {
        var rows = listOf(safRow("rule", "Ado - RuLe.flac"))
        val reader = FakeTagReader("RuLe" to LocalTags(title = "RuLe", album = "Kyogen", trackNumber = 4))
        val provider = LocalMusicProvider(LocalTrackSource { rows }, tagReader = reader)
        provider.refresh()
        provider.enrichTags()

        provider.refresh()
        assertEquals("Kyogen", provider.libraryTracks.value.single().album?.title)
        provider.enrichTags()
        assertEquals(listOf("RuLe"), reader.opened)

        rows = listOf(safRow("rule", "Ado - RuLe.flac").copy(fileModifiedEpochMs = 9_000))
        provider.refresh()
        assertEquals("Downloads", provider.libraryTracks.value.single().album?.title)
        provider.enrichTags()
        assertEquals(listOf("RuLe", "RuLe"), reader.opened)
        assertEquals("Kyogen", provider.libraryTracks.value.single().album?.title)
    }

    @Test fun firstPlayReadsTagsOnceAndAFileThatCannotBeOpenedIsRetriedLater() = runTest {
        val reader = FakeTagReader("RuLe" to LocalTags(title = "RuLe", trackNumber = 4))
        val provider = LocalMusicProvider(
            LocalTrackSource { listOf(safRow("rule", "Ado - RuLe.flac"), safRow("locked", "Locked.flac")) },
            tagReader = reader,
        )
        val (rule, locked) = provider.refresh()
        assertTrue(provider.needsTags(rule))

        val tagged = assertNotNull(provider.resolveTags(rule))
        assertEquals("RuLe", tagged.title)
        assertEquals(rule.canonicalId, tagged.canonicalId)
        assertFalse(provider.needsTags(tagged))
        assertEquals(tagged, provider.resolveTags(tagged), "cached tags must reach Now Playing without reopening")

        assertNull(provider.resolveTags(locked))
        assertTrue(provider.needsTags(locked), "a failed open is not remembered as read")
    }

    @Test fun withoutAReaderNothingIsPendingAndDesktopRowsAreUntouched() = runTest {
        val provider = LocalMusicProvider(LocalTrackSource { listOf(safRow("rule", "Ado - RuLe.flac")) })
        val (rule) = provider.refresh()
        assertFalse(provider.needsTags(rule))
        assertNull(provider.resolveTags(rule))
    }

    @Test fun tagsDoNotChangeLocalOrPortableSyncIdentities() = runTest {
        val reader = FakeTagReader(
            "RuLe" to LocalTags(title = "RuLe", artists = listOf("Ado"), album = "Kyogen", albumArtist = "Ado", trackNumber = 4),
            "Solitude" to LocalTags(title = "Solitude", artists = listOf("re:plus"), album = "Ordinary Landscape"),
        )
        val provider = LocalMusicProvider(
            LocalTrackSource {
                listOf(
                    safRow("keyed", "Ado - RuLe.flac", contentKey = key),
                    safRow("plain", "y2mate.com - re_plus - Solitude_dldnT46oCrs_320kbps.mp3"),
                )
            },
            tagReader = reader,
        )
        val before = provider.refresh().map(::identities)
        assertEquals("localkey:$key", before[0].portableId)
        assertEquals("localfile:y2mate.com - re_plus - solitude_dldnt46ocrs_320kbps.mp3", before[1].portableId)
        assertEquals(before.map { it.portableId }, before.map { it.exported }, "hearts export the same ids")
        assertEquals(listOf("ado - rule.flac", "y2mate.com - re_plus - solitude_dldnt46ocrs_320kbps.mp3"), before.map { it.basename })

        provider.enrichTags()

        val after = provider.libraryTracks.value
        assertEquals(listOf("RuLe", "Solitude"), after.map { it.title }, "tags were applied")
        assertEquals(before, after.map(::identities))
    }

    @Test fun albumArtistGroupsAnAlbumWhoseTracksCreditDifferentArtists() = runTest {
        val folder = "/music/Kyogen"
        fun row(id: String, artist: String, track: Int, disc: Int = 1) = LocalTrack(
            id = id, title = "Song $id", artists = listOf(artist), album = "Kyogen", albumGroupKey = folder,
            albumArtist = "Ado", trackNumber = track, discNumber = disc, location = "$folder/$id.flac",
            tagsVersion = LOCAL_TAGS_VERSION,
        )
        val provider = LocalMusicProvider(
            LocalTrackSource { listOf(row("c", "Ado", 1, disc = 2), row("b", "Ado feat. Vaundy", 2), row("a", "Ado", 1)) },
        )
        val tracks = provider.refresh()
        assertEquals(1, tracks.map { it.album?.canonicalId }.distinct().size)
        assertEquals(listOf("a", "b", "c"), tracks.inAlbumOrder().map { it.title.removePrefix("Song ") })
    }

    @Test fun albumPinsFollowTagsAcrossPersistenceAndARescan() = runTest {
        val library = com.universalmusic.player.data.library.LibraryRepository()
        val cache = MemoryScanCache()
        val provider = LocalMusicProvider(
            LocalTrackSource { listOf(safRow("rule", "Ado - RuLe.flac")) },
            cache, { "k" }, tagReader = FakeTagReader("RuLe" to LocalTags(album = "Kyogen", albumArtist = "Ado")),
            onAlbumsRekeyed = library::remapLocalAlbumPins,
        )
        val before = provider.refresh().single().album!!
        library.pinHome(com.universalmusic.player.data.library.HomePinKind.ALBUM, before.canonicalId, before.title)
        provider.enrichTags()
        provider.refresh()
        val restoredRows = cache.read("k")!!.map { it.toStored().toLocalTrackOrNull()!! }
        val restarted = LocalMusicProvider(LocalTrackSource { emptyList() }, onAlbumsRekeyed = library::remapLocalAlbumPins)
        library.unpinHome(library.homePins.value.single().id)
        library.pinHome(com.universalmusic.player.data.library.HomePinKind.ALBUM, before.canonicalId, before.title)
        restarted.applyCachedTracks(restoredRows)
        val pin = library.homePins.value.single()
        assertTrue(provider.libraryTracks.value.any { it.album?.canonicalId == pin.targetId }, "pin must still resolve after tags")
    }

    @Test fun aGuessedAlbumPinSplitsIntoTaggedAlbumsAndKeepsItsPosition() = runTest {
        val library = com.universalmusic.player.data.library.LibraryRepository()
        val provider = LocalMusicProvider(
            LocalTrackSource { listOf(safRow("a", "Ado - RuLe.flac"), safRow("b", "Ado - Solitude.flac")) },
            tagReader = FakeTagReader("RuLe" to LocalTags(album = "One"), "Solitude" to LocalTags(album = "Two")),
            onAlbumsRekeyed = library::remapLocalAlbumPins,
        )
        val old = provider.refresh().first().album!!
        val pin = library.pinHome(com.universalmusic.player.data.library.HomePinKind.ALBUM, old.canonicalId, old.title)!!
        provider.resolveTags(provider.libraryTracks.value.first())
        assertEquals(provider.libraryTracks.value.map { it.album!!.canonicalId }.toSet(), library.homePins.value.map { it.targetId }.toSet())
        provider.enrichTags()
        assertEquals(setOf("One", "Two"), library.homePins.value.map { it.title }.toSet())
        assertEquals(pin.id, library.homePins.value.first().id)
        assertEquals(provider.libraryTracks.value.map { it.album!!.canonicalId }.toSet(), library.homePins.value.map { it.targetId }.toSet())
        provider.refresh()
        assertEquals(2, library.homePins.value.size)
    }

    @Test fun tagsReadByTheBackgroundPassAreReturnedToFirstPlay() = runTest {
        val provider = LocalMusicProvider(
            LocalTrackSource { listOf(safRow("rule", "Ado - RuLe.flac")) },
            tagReader = FakeTagReader("RuLe" to LocalTags(title = "Tagged title")),
        )
        val original = provider.refresh().single()
        provider.enrichTags()
        assertEquals("Tagged title", provider.resolveTags(original)?.title)
    }

    @Test fun aCancelledReaderCannotPublishItsLastBatch() = runTest {
        val provider = LocalMusicProvider(
            LocalTrackSource { listOf(safRow("rule", "Ado - RuLe.flac")) },
            tagReader = object : LocalTagReader {
                override suspend fun read(location: String): LocalTags {
                    kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!.cancel()
                    return LocalTags(title = "Cancelled tags")
                }
            },
        )
        val original = provider.refresh().single()
        val job = launch { provider.enrichTags() }
        job.join()
        assertEquals(original, provider.libraryTracks.value.single())
    }

    private data class Identities(val canonicalId: String, val portableId: String?, val exported: String?, val basename: String?)

    private fun identities(track: Track): Identities {
        val export = track.toPersisted(0).toLocalFileHeartExport("phone", 1)
        return Identities(
            canonicalId = track.canonicalId,
            portableId = track.localSyncIdentity()?.portableId,
            exported = export?.first?.canonicalId,
            basename = export?.second?.localSyncIdentity()?.basename,
        )
    }
}

/** Answers by a fragment of the file name; "Locked" files cannot be opened. Records what it opened. */
private class FakeTagReader(vararg tags: Pair<String, LocalTags>) : LocalTagReader {
    private val byFragment = tags.toMap()
    val opened = mutableListOf<String>()

    override suspend fun read(location: String): LocalTags? {
        val fragment = (byFragment.keys + "Locked" + "Different").first { it in location }
        opened += fragment
        return if (fragment == "Locked") null else byFragment[fragment] ?: LocalTags()
    }
}

private class MemoryScanCache : LocalLibraryScanCache {
    private val entries = mutableMapOf<String, List<LocalTrack>>()
    override suspend fun read(configKey: String): List<LocalTrack>? = entries[configKey]
    override suspend fun write(configKey: String, tracks: List<LocalTrack>) {
        entries[configKey] = tracks
    }
}
