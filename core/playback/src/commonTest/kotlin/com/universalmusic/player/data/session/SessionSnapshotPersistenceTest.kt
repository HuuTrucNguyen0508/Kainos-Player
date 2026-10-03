package com.universalmusic.player.data.session

import com.universalmusic.player.data.library.PersistedArtist
import com.universalmusic.player.data.library.PersistedSource
import com.universalmusic.player.data.library.PersistedTrack
import com.universalmusic.player.domain.matching.track
import com.universalmusic.player.domain.model.ArtistRef
import com.universalmusic.player.domain.model.AudioQuality
import com.universalmusic.player.domain.model.PlaybackHandle
import com.universalmusic.player.domain.model.PlaybackQueue
import com.universalmusic.player.domain.model.PlaybackSource
import com.universalmusic.player.domain.model.ProviderId
import com.universalmusic.player.domain.model.QualityTier
import com.universalmusic.player.domain.model.QueueItem
import com.universalmusic.player.domain.model.RepeatMode
import com.universalmusic.player.domain.model.Track
import com.universalmusic.player.domain.playback.DefaultSourceResolver
import com.universalmusic.player.domain.playback.EngineState
import com.universalmusic.player.domain.playback.EngineStatus
import com.universalmusic.player.domain.playback.PlaybackEngine
import com.universalmusic.player.domain.playback.PlayerSession
import com.universalmusic.player.domain.playback.SessionPersistenceController
import com.universalmusic.player.domain.queue.QueueController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionSnapshotPersistenceTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun roundTripsQueueShuffleOrderAndIdsWithoutStreamUrls() {
        val queue = PlaybackQueue(
            items = listOf(
                QueueItem("qid-local", localTrack()),
                QueueItem("qid-spotify", spotifyTrack(streamUrl = "https://audio.spotify.example/expire=9")),
                QueueItem("qid-yt", youtubeTrack(streamUrl = "https://googlevideo.example/expire=1")),
            ),
            currentIndex = 1,
            shuffle = true,
            repeat = RepeatMode.ALL,
            shuffleOrder = listOf(1, 2, 0),
        )
        val snapshot = queue.toSessionSnapshot(positionMs = 42_000L, nowMs = 1_000L)
        val encoded = json.encodeToString(snapshot)
        assertFalse(encoded.contains("https://audio.spotify.example"))
        assertFalse(encoded.contains("https://googlevideo.example"))
        assertFalse(encoded.contains("streamUrl"))

        val decoded = json.decodeFromString<SessionSnapshot>(encoded).migrated()
        assertEquals(SESSION_SNAPSHOT_FORMAT_VERSION, decoded.version)
        assertEquals(listOf("qid-local", "qid-spotify", "qid-yt"), decoded.items.map { it.id })
        assertEquals(listOf(1, 2, 0), decoded.shuffleOrder)
        assertEquals(42_000L, decoded.positionMs)
        assertNull(decoded.items[1].track.sources.single().localLocation)
        assertNull(decoded.items[2].track.sources.single().localLocation)
        assertEquals("file:///music/song.flac", decoded.items[0].track.sources.single().localLocation)

        val restored = decoded.toPlaybackQueue()
        assertEquals(listOf("qid-local", "qid-spotify", "qid-yt"), restored.items.map { it.id })
        assertEquals(listOf(1, 2, 0), restored.shuffleOrder)
        assertEquals(1, restored.currentIndex)
        assertTrue(restored.shuffle)
        assertEquals(RepeatMode.ALL, restored.repeat)
        assertNull(restored.items[1].track.sources.single().streamUrl)
        assertNull(restored.items[2].track.sources.single().streamUrl)
        assertTrue(restored.items[1].track.sources.single().handle is PlaybackHandle.ProviderPlayback)
        assertEquals(
            PlaybackHandle.Url("file:///music/song.flac"),
            restored.items[0].track.sources.single().handle,
        )
    }

    @Test
    fun corruptSnapshotRecoversToEmpty() = runTest {
        val store = InMemorySessionSnapshotStore()
        store.rawOverride = "{not-json"
        val read = store.read()
        assertEquals(emptyList(), read.items)
        assertEquals(0, read.currentIndex)
    }

    @Test
    fun queueControllerRestorePreservesEntryIdsAndShuffleOrder() {
        val queue = QueueController { error("must not mint ids on restore") }
        queue.restore(
            PlaybackQueue(
                items = listOf(
                    QueueItem("keep-a", track("A", "X", provider = ProviderId.SPOTIFY)),
                    QueueItem("keep-b", track("B", "X", provider = ProviderId.SPOTIFY)),
                    QueueItem("keep-c", track("C", "X", provider = ProviderId.SPOTIFY)),
                ),
                currentIndex = 2,
                shuffle = true,
                repeat = RepeatMode.ONE,
                shuffleOrder = listOf(2, 0, 1),
            ),
        )
        val value = queue.queue.value
        assertEquals(listOf("keep-a", "keep-b", "keep-c"), value.items.map { it.id })
        assertEquals(listOf(2, 0, 1), value.shuffleOrder)
        assertEquals("C", value.current?.track?.title)
        assertEquals(RepeatMode.ONE, value.repeat)
    }

    @Test
    fun restorePausedDoesNotStartEngine() = runTest {
        val engine = CountingEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        val snapshot = PlaybackQueue(
            items = listOf(
                QueueItem("qid-1", track("One", "A", provider = ProviderId.SAMPLE)),
                QueueItem("qid-2", track("Two", "B", provider = ProviderId.SAMPLE)),
            ),
            currentIndex = 1,
            shuffle = true,
            shuffleOrder = listOf(1, 0),
            repeat = RepeatMode.ALL,
        ).toSessionSnapshot(positionMs = 12_500L, nowMs = 99L)

        session.restorePaused(snapshot)
        runCurrent()

        assertEquals("Two", session.nowPlaying.value.track?.title)
        assertEquals("qid-2", session.nowPlaying.value.queueItemId)
        assertEquals(12_500L, session.nowPlaying.value.positionMs)
        assertFalse(session.nowPlaying.value.isPlaying)
        assertFalse(session.nowPlaying.value.buffering)
        assertEquals(listOf(1, 0), session.queue.queue.value.shuffleOrder)
        assertEquals(0, engine.playCalls)
    }

    @Test
    fun persistenceControllerRestoresPausedAndRoundTrips() = runTest {
        val store = InMemorySessionSnapshotStore()
        val engine = CountingEngine()
        val session = PlayerSession(engine, DefaultSourceResolver(), backgroundScope)
        val controller = SessionPersistenceController(
            player = session,
            store = store,
            scope = backgroundScope,
            writeDebounceMs = 0L,
            positionPersistIntervalMs = 5_000L,
            clock = { 5_000L },
        )

        session.restorePaused(
            SessionSnapshot(
                items = listOf(
                    PersistedQueueItem(
                        id = "entry-1",
                        track = PersistedTrack(
                            canonicalId = "spotify:1",
                            title = "Heart",
                            artists = listOf(PersistedArtist("a", "Artist")),
                            durationMs = 200_000,
                            sources = listOf(
                                PersistedSource(
                                    provider = ProviderId.SPOTIFY.name,
                                    providerTrackId = "1",
                                    localLocation = null,
                                ),
                            ),
                        ),
                    ),
                ),
                currentIndex = 0,
                shuffle = false,
                repeat = RepeatMode.OFF.name,
                positionMs = 8_000L,
            ),
        )
        assertEquals(0, engine.playCalls)
        assertFalse(session.nowPlaying.value.isPlaying)

        session.addToQueue(track("Extra", "X", provider = ProviderId.SAMPLE))
        controller.flush()

        val onDisk = store.read()
        assertEquals(2, onDisk.items.size)
        assertEquals("entry-1", onDisk.items.first().id)
        assertNull(onDisk.items.first().track.sources.single().localLocation)
        val encoded = json.encodeToString(onDisk)
        assertFalse(encoded.contains("streamUrl"))

        // Cold restore from disk stays paused.
        val engine2 = CountingEngine()
        val session2 = PlayerSession(engine2, DefaultSourceResolver(), backgroundScope)
        val controller2 = SessionPersistenceController(
            player = session2,
            store = store,
            scope = backgroundScope,
        )
        controller2.restore()
        assertEquals("Heart", session2.nowPlaying.value.track?.title)
        assertEquals("entry-1", session2.nowPlaying.value.queueItemId)
        assertEquals(8_000L, session2.nowPlaying.value.positionMs)
        assertFalse(session2.nowPlaying.value.isPlaying)
        assertEquals(0, engine2.playCalls)
    }
}

private class CountingEngine : PlaybackEngine {
    override val state = MutableStateFlow(EngineState())
    var playCalls: Int = 0
        private set

    override suspend fun play(handle: PlaybackHandle, quality: AudioQuality?, playGeneration: Long) {
        playCalls++
        state.value = EngineState(
            status = EngineStatus.PLAYING,
            durationMs = 1_000,
            playGeneration = playGeneration,
        )
    }

    override fun pause() {
        state.value = state.value.copy(status = EngineStatus.PAUSED)
    }

    override fun resume() {
        state.value = state.value.copy(status = EngineStatus.PLAYING)
    }

    override fun seekTo(positionMs: Long) {
        state.value = state.value.copy(positionMs = positionMs)
    }

    override fun stop() {
        state.value = EngineState()
    }

    override fun setVolume(volume: Float) = Unit
}

private class InMemorySessionSnapshotStore : SessionSnapshotStore {
    var snapshot = SessionSnapshot()
    var rawOverride: String? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun read(): SessionSnapshot {
        rawOverride?.let { raw ->
            return runCatching {
                json.decodeFromString<SessionSnapshot>(raw).migrated()
            }.getOrDefault(SessionSnapshot())
        }
        return snapshot.migrated()
    }

    override suspend fun write(snapshot: SessionSnapshot) {
        rawOverride = null
        this.snapshot = snapshot
    }
}

private fun localTrack() = Track(
    canonicalId = "local:1",
    title = "Local Song",
    artists = listOf(ArtistRef("local-artist:1", "Local Artist")),
    durationMs = 200_000,
    sources = listOf(
        PlaybackSource(
            provider = ProviderId.LOCAL,
            providerTrackId = "1",
            streamUrl = "file:///music/song.flac",
            isPlayable = true,
            handle = PlaybackHandle.Url("file:///music/song.flac"),
        ),
    ),
)

private fun spotifyTrack(streamUrl: String? = null) = Track(
    canonicalId = "spotify:abc",
    title = "Spotify Song",
    artists = listOf(ArtistRef("spotify-artist:1", "Spotify Artist")),
    durationMs = 210_000,
    sources = listOf(
        PlaybackSource(
            provider = ProviderId.SPOTIFY,
            providerTrackId = "abc",
            streamUrl = streamUrl,
            quality = AudioQuality(QualityTier.LOSSLESS),
            isPlayable = true,
            handle = if (streamUrl != null) {
                PlaybackHandle.Url(streamUrl)
            } else {
                PlaybackHandle.ProviderPlayback(ProviderId.SPOTIFY, "abc", 210_000)
            },
        ),
    ),
)

private fun youtubeTrack(streamUrl: String? = null) = Track(
    canonicalId = "yt:abc",
    title = "YT Song",
    artists = listOf(ArtistRef("yt-artist:1", "YT Artist")),
    durationMs = 180_000,
    sources = listOf(
        PlaybackSource(
            provider = ProviderId.YOUTUBE_MUSIC,
            providerTrackId = "abc",
            streamUrl = streamUrl,
            quality = AudioQuality(QualityTier.STANDARD, codec = "opus"),
            isPlayable = true,
            handle = if (streamUrl != null) {
                PlaybackHandle.Url(streamUrl)
            } else {
                PlaybackHandle.ProviderPlayback(ProviderId.YOUTUBE_MUSIC, "abc", 180_000)
            },
        ),
    ),
)
