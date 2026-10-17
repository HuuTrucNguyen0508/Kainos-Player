package com.universalmusic.player.data.session

import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QueueHashTest {
    @Test
    fun canonicalBytesAndHashesMatchIndependentVectors() {
        assertHash(emptyList(), "[]", "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945")
        assertHash(
            listOf("q1" to "yt:abc", "q2" to "spotify:def"),
            """[["q1","yt:abc"],["q2","spotify:def"]]""",
            "ea1879f75a73d2936fdbeaf3fc01a32e4dd49f900f810eaae8c08e2d9a4ac467",
        )
        assertHash(
            listOf("q2" to "spotify:def", "q1" to "yt:abc"),
            """[["q2","spotify:def"],["q1","yt:abc"]]""",
            "cb8107a2ce8206e1a2503ec5772cb890794cbeff4bbd2fbaf8a197f804ea5315",
        )
        assertHash(
            listOf("e1" to "localfile:a\tb \"q\" \\ é/✓.flac", "e2" to "localkey:lc1:" + "a".repeat(64)),
            """[["e1","localfile:a\u0009b \"q\" \\ é/✓.flac"],["e2","localkey:lc1:${"a".repeat(64)}"]]""",
            "a8c0547b2b02ee650bfc497204f4d0708f7fb5493513ef266d3ecbf387029200",
        )
        assertHash(
            listOf("x\n" to "\u001f🎵"),
            """[["x\u000a","\u001f🎵"]]""",
            "47682607c53d2559dde3bbe6b7173d5b22bfe30ab99be2ba26895922021d73d4",
        )
    }

    @Test
    fun unpairedSurrogatesAreRejected() {
        assertFailsWith<IllegalArgumentException> { canonicalQueueJson(listOf("q1" to "yt:\uD800")) }
        assertFailsWith<IllegalArgumentException> { canonicalQueueJson(listOf("\uDC00" to "yt:a")) }
    }

    @Test
    fun hashCoversOrderedIdentitiesOnly() {
        val queue = complete(listOf(QueueItem("q1", youtube("abc")), QueueItem("q2", spotify("def"))))
        assertEquals("ea1879f75a73d2936fdbeaf3fc01a32e4dd49f900f810eaae8c08e2d9a4ac467", queue.hash.hex)

        val restamped = complete(listOf(
            QueueItem("q1", youtube("abc").copy(cachedAtMs = 99, artworkUrl = "https://img.example/new.jpg", title = "Renamed")),
            QueueItem("q2", spotify("def").copy(cachedAtMs = 7, durationMs = 1)),
        ))
        assertEquals(queue.hash, restamped.hash)

        val reordered = complete(listOf(QueueItem("q2", spotify("def")), QueueItem("q1", youtube("abc"))))
        assertEquals("cb8107a2ce8206e1a2503ec5772cb890794cbeff4bbd2fbaf8a197f804ea5315", reordered.hash.hex)

        val renumbered = complete(listOf(QueueItem("q9", youtube("abc")), QueueItem("q2", spotify("def"))))
        assertNotEquals(queue.hash, renumbered.hash)
    }

    @Test
    fun duplicateTracksNeedDistinctEntryIds() {
        val repeated = complete(listOf(QueueItem("q1", youtube("abc")), QueueItem("q2", youtube("abc"))))
        assertEquals(listOf("yt:abc", "yt:abc"), repeated.entries.map { it.track.identity })
        assertEquals(
            QueueProjection.Unsupported("q1", ProjectionFailure.DUPLICATE_ITEM_ID),
            PortableListeningQueue.project(listOf(QueueItem("q1", youtube("abc")), QueueItem("q1", spotify("def")))),
        )
    }

    @Test
    fun deviceLocalTracksBecomeBasenameOrContentKeyIdentities() {
        val key = "lc1:" + "b".repeat(64)
        val queue = complete(listOf(
            QueueItem("q1", local("/home/me/Music/It's \"Live\": Take 2 (feat. A & B).FLAC")),
            QueueItem("q2", local("content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FSong.flac")),
            QueueItem("q3", local("file:///home/me/Music/keyed.flac", contentKey = key)),
        ))
        assertEquals(
            listOf("localfile:it's \"live\": take 2 (feat. a & b).flac", "localfile:song.flac", "localkey:$key"),
            queue.entries.map { it.track.identity },
        )
        val json = queue.encodeJson()
        listOf("/home/me", "content://", "file://", "primary%3A", "localLocation\":\"").forEach {
            assertFalse(it in json, "portable payload leaked $it: $json")
        }
        assertEquals(queue, PortableListeningQueue.decode(json, queue.hash))
    }

    @Test
    fun providerTracksDropLocalLocationsAndKeepRemoteArtworkAsDisplayOnly() {
        val track = spotify("def").copy(
            artworkUrl = "https://i.scdn.co/image/x",
            sources = listOf(
                PersistedSource("SPOTIFY", "def", localLocation = "/cache/spotify/def.ogg"),
                PersistedSource("YOUTUBE_MUSIC", "vid123"),
                PersistedSource("LOCAL", "def", localLocation = "/music/def.flac"),
            ),
        )
        val portable = PortableListeningTrack.project(track)!!.track
        assertEquals(listOf(PersistedSource("SPOTIFY", "def"), PersistedSource("YOUTUBE_MUSIC", "vid123")), portable.sources)
        assertEquals("https://i.scdn.co/image/x", portable.artworkUrl)
        assertNull(PortableListeningTrack.project(spotify("def").copy(artworkUrl = "file:///cover.jpg"))?.track?.artworkUrl)
    }

    @Test
    fun unsupportedEntriesFailTheWholeProjection() {
        val sample = PersistedTrack("sample:1", "Sample", sources = listOf(PersistedSource("SAMPLE", "1")))
        assertEquals(
            QueueProjection.Unsupported("q2", ProjectionFailure.UNSUPPORTED_TRACK),
            PortableListeningQueue.project(listOf(QueueItem("q1", youtube("abc")), QueueItem("q2", sample))),
        )
        assertEquals(
            QueueProjection.Unsupported("q1", ProjectionFailure.UNSUPPORTED_TRACK),
            PortableListeningQueue.project(listOf(QueueItem("q1", youtube("https://evil.example/stream")))),
        )
    }

    @Test
    fun parseRejectsLocationsThatAnUntrustedPayloadCouldCarry() {
        val portableLocal = PersistedTrack("localfile:song.flac", "Song", sources = listOf(PersistedSource("LOCAL", "song.flac")))
        assertTrue(PortableListeningTrack.parse(portableLocal) != null)
        listOf(
            portableLocal.copy(canonicalId = "localfile:../song.flac", sources = listOf(PersistedSource("LOCAL", "../song.flac"))),
            portableLocal.copy(canonicalId = "localfile:..", sources = listOf(PersistedSource("LOCAL", ".."))),
            portableLocal.copy(canonicalId = "localfile:a\u0000b", sources = listOf(PersistedSource("LOCAL", "a\u0000b"))),
            portableLocal.copy(sources = listOf(PersistedSource("LOCAL", "song.flac", localLocation = "/music/song.flac"))),
            portableLocal.copy(sources = listOf(PersistedSource("LOCAL", "other.flac"))),
            portableLocal.copy(cachedAtMs = 5),
            portableLocal.copy(canonicalId = "local:42"),
            youtube("abc").copy(sources = listOf(PersistedSource("YOUTUBE_MUSIC", "https://googlevideo.example/x"))),
        ).forEach { assertNull(PortableListeningTrack.parse(it), "accepted $it") }
    }

    @Test
    fun listeningStateRequiresTheCurrentEntryToBelongToItsQueue() {
        val queue = complete(listOf(QueueItem("q1", youtube("abc"))))
        assertEquals("yt:abc", ListeningState.create(queue, "q1", 5, 10, true).current?.track?.identity)
        assertNull(ListeningState.create(queue, null, 0, 10, false).current)
        assertFailsWith<IllegalArgumentException> { ListeningState.create(queue, "other", 0, 10, false) }
    }

    @Test
    fun decodeRejectsAPayloadThatDoesNotMatchItsHash() {
        val queue = complete(listOf(QueueItem("q1", youtube("abc"))))
        val other = complete(listOf(QueueItem("q2", youtube("abc"))))
        assertFailsWith<IllegalArgumentException> { PortableListeningQueue.decode(queue.encodeJson(), other.hash) }
    }

    private fun assertHash(entries: List<Pair<String, String>>, bytes: String, hex: String) {
        assertEquals(bytes, canonicalQueueJson(entries))
        assertEquals(hex, QueueHash.of(entries).hex)
    }
}

private typealias QueueItem = PersistedQueueItem

private fun complete(items: List<PersistedQueueItem>): PortableListeningQueue =
    (PortableListeningQueue.project(items) as QueueProjection.Complete).queue

private fun youtube(id: String) = PersistedTrack(
    canonicalId = "yt:$id",
    title = "YT $id",
    artists = listOf(PersistedArtist("yt-artist:1", "Artist")),
    sources = listOf(PersistedSource("YOUTUBE_MUSIC", id)),
    cachedAtMs = 1_000,
)

private fun spotify(id: String) = PersistedTrack(
    canonicalId = "spotify:$id",
    title = "Spotify $id",
    sources = listOf(PersistedSource("SPOTIFY", id)),
    cachedAtMs = 1_000,
)

private fun local(location: String, contentKey: String? = null) = PersistedTrack(
    canonicalId = "local:${location.hashCode()}",
    title = "Local",
    sources = listOf(PersistedSource("LOCAL", location.substringAfterLast('/'), localLocation = location)),
    cachedAtMs = 1_000,
    localContentKey = contentKey,
)
